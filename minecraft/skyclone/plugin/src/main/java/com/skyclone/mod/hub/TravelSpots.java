package com.skyclone.mod.hub;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The hub's own exits besides the Portal Plaza (research 03 section 1.1): launch pads
 * ({@code pad.<warp>} areas) fling you into the air and land you on the next island, and walk-in
 * gates ({@code gate.<warp>}, the Coal Mine's tunnel to the Gold Mine) warp on contact like a
 * portal. Both check the warp's real requirements BEFORE anything happens, so a locked pad never
 * throws you into the air for nothing. Only block-changing moves inside the hub world are looked
 * at, against four boxes.
 */
final class TravelSpots implements Listener {

    /** A pad or gate as the layout describes it. */
    record Spot(String area, String warp, boolean pad, Box box) {}

    private List<Spot> spots;
    private Layout seen;
    private final Map<UUID, Long> cooldown = new HashMap<>();
    /** Players mid-flight: the time their fall-damage immunity ends. */
    private final Map<UUID, Long> flying = new HashMap<>();
    private final Map<UUID, Long> lastRefusal = new HashMap<>();

    /** The pads and gates of the current hub layout (re-read when the layout object changes). */
    List<Spot> spots() {
        Layout l = Sky.worlds().layout(HubModule.ISLAND);
        if (l == null) return List.of();
        if (l != seen || spots == null) {
            List<Spot> out = new ArrayList<>();
            for (Map.Entry<String, Box> e : l.areas().entrySet()) {
                String n = e.getKey();
                if (n.startsWith("pad.")) out.add(new Spot(n, n.substring(4), true, e.getValue()));
                else if (n.startsWith("gate.")) out.add(new Spot(n, n.substring(5), false, e.getValue()));
            }
            spots = out;
            seen = l;
        }
        return spots;
    }

    /** HIGH, not MONITOR: a refused walk-in gate holds the player back by changing the move's destination. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo(), from = e.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY() && to.getBlockZ() == from.getBlockZ()) return;
        if (!HubModule.ISLAND.equals(Sky.worlds().islandOf(to.getWorld()))) return;
        if (!HubModule.travelSpots.get()) return;
        for (Spot s : spots()) {
            if (!inside(s.box(), to)) continue;
            trigger(e, e.getPlayer(), s);
            return;
        }
    }

    /** Feet or head inside the spot's box (a 2-high portal sheet is entered at either). */
    static boolean inside(Box b, Location at) {
        return b.contains(at.getX(), at.getY(), at.getZ()) || b.contains(at.getX(), at.getY() + 1, at.getZ());
    }

    /**
     * A refused move into this spot is undone outright (the player stays where they were) when the spot is a
     * walk-in gate and the player was outside it. Wave-3 playtest: the Rift arch pushed a player without an
     * infusion back once, then the 3-second cooldown let anyone still holding forward walk straight through
     * the one-block sheet and out behind the arch. Pads stay a nudge: they are wide floors you can be
     * standing on already (a refused pad under your feet must push you off, not pin you there).
     */
    static boolean holdsBack(Spot s, Location from) { return !s.pad() && !inside(s.box(), from); }

    /** Why this player may not use the warp right now (null = they may), checked without side effects. */
    static String blocker(Player p, String warpId) {
        WorldService.WarpInfo w = HubMenus.warp(warpId);
        if (w == null) return "&cThis way leads nowhere yet.";
        String req = HubMenus.requirementOf(w);
        if (req != null && !Sky.requirements().check(p, req)) return "&cRequires " + Sky.requirements().describe(req) + "&c!";
        IslandDef isl = Sky.worlds().island(w.island());
        if (isl != null && !isl.instanced && !Sky.worlds().isBuilt(w.island())) return "&c" + w.displayName() + " is still being built - try again shortly!";
        return null;
    }

    /** Called from the move event (null for {@code /sb hub launch}, which only checks and goes). */
    void trigger(PlayerMoveEvent e, Player p, Spot s) {
        long now = System.currentTimeMillis();
        String why = blocker(p, s.warp());
        if (why != null) {
            // the refusal is enforced on EVERY move into the spot; only the message and sound are throttled
            boolean loud = now >= cooldown.getOrDefault(p.getUniqueId(), 0L);
            if (e != null && holdsBack(s, e.getFrom())) {
                Location back = e.getFrom().clone();
                back.setYaw(e.getTo().getYaw());
                back.setPitch(e.getTo().getPitch());
                e.setTo(back);
            }
            if (loud) {
                cooldown.put(p.getUniqueId(), now + 1000L);
                if (now - lastRefusal.getOrDefault(p.getUniqueId(), 0L) > 5000L) { Msg.send(p, why); lastRefusal.put(p.getUniqueId(), now); }
                Msg.sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
                // nudge them back off the pad/gate so they are not stuck re-triggering it
                p.setVelocity(nudge(s.box(), e != null && !s.pad() ? e.getFrom() : p.getLocation()).multiply(0.6).setY(0.25));
            }
            return;
        }
        if (now < cooldown.getOrDefault(p.getUniqueId(), 0L)) return;
        cooldown.put(p.getUniqueId(), now + 3000L);
        if (!s.pad()) {
            if (Sky.worlds().warp(p, s.warp())) arrived(p, s.warp(), false);
            return;
        }
        launch(p, s.warp());
    }

    /**
     * Which way to push a refused player so they leave the spot. A one-block-thick gate (the Rift arch's
     * portal sheet, 1 x 2 x 5) must be left ACROSS its thin side: "away from the centre" pointed along the
     * sheet for anyone who walked in off-centre and slid them sideways inside the portal, where the gate
     * fired again three seconds later. Pads (wide and flat) still push away from their centre.
     * Horizontal unit vector.
     */
    static Vector nudge(Box b, Location at) {
        double cx = (b.minX() + b.maxX() + 1) / 2.0, cz = (b.minZ() + b.maxZ() + 1) / 2.0;
        double dx = at.getX() - cx, dz = at.getZ() - cz;
        int wx = b.maxX() - b.minX() + 1, wz = b.maxZ() - b.minZ() + 1;
        Vector look = at.getDirection().multiply(-1);
        if (wx < wz) {                    // thin along x: leave east or west, the side they came from
            double sx = Math.abs(dx) > 0.05 ? Math.signum(dx) : Math.signum(look.getX());
            return new Vector(sx == 0 ? 1 : sx, 0, 0);
        }
        if (wz < wx) {
            double sz = Math.abs(dz) > 0.05 ? Math.signum(dz) : Math.signum(look.getZ());
            return new Vector(0, 0, sz == 0 ? 1 : sz);
        }
        Vector back = new Vector(dx, 0, dz);
        if (back.lengthSquared() < 0.01) back = look.setY(0);
        if (back.lengthSquared() < 1e-6) back = new Vector(1, 0, 0);
        return back.normalize();
    }

    /** Fling the player up and forward, then warp them when the arc peaks. */
    void launch(Player p, String warpId) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 0.01) dir = new Vector(0, 0, 1);
        p.setVelocity(dir.normalize().multiply(0.9).setY(HubModule.padPower.get()));
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 24, 0.6, 0.2, 0.6, 0.05);
        p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 0.9f);
        p.playSound(p.getLocation(), Sound.BLOCK_SLIME_BLOCK_FALL, 1f, 0.8f);
        flying.put(p.getUniqueId(), System.currentTimeMillis() + 10_000L);
        if (Sky.profiles().isReady(p)) HubModule.data(p).padsRidden++;
        HubGuides.progress(p, "pad:" + warpId, 1);
        int ticks = Math.max(5, HubModule.padFlightTicks.get());
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            if (!HubModule.ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) return;   // left on their own
            p.setFallDistance(0);
            if (Sky.worlds().warp(p, warpId)) arrived(p, warpId, true);
        }, ticks);
    }

    static void arrived(Player p, String warpId, boolean pad) {
        WorldService.WarpInfo w = HubMenus.warp(warpId);
        String name = w == null ? Text.titleCase(warpId) : w.displayName();
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            p.setFallDistance(0);
            p.showTitle(Title.title(Text.c("&b" + name), Text.c(pad ? "&7Launched!" : "&7Warped!"),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1200), Duration.ofMillis(400))));
        }, 2L);
    }

    /** No fall damage for a few seconds after a launch (the warp might have been refused mid-air). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        Long until = flying.get(p.getUniqueId());
        if (until == null) return;
        if (System.currentTimeMillis() > until) { flying.remove(p.getUniqueId()); return; }
        e.setCancelled(true);
        flying.remove(p.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        cooldown.remove(id);
        flying.remove(id);
        lastRefusal.remove(id);
    }

    @EventHandler
    public void onBuilt(SkyEvents.IslandBuilt e) {
        if (HubModule.ISLAND.equals(e.islandId)) spots = null;
    }
}
