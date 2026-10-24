package com.skyclone.mod.mines;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.ui.Msg;
import com.skyclone.mod.mines.gen.RailRoute;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wave 5: real rides. A minecart that rolls along the Dwarven Mines' laid track, and a lift cage that drops down
 * the Deep Caverns shaft (and on into the Dwarven Mines). Nothing is a menu teleport any more: the player sits in
 * a moving vehicle and watches the cavern go by.
 *
 * <p>How it moves: the vehicle is an invisible block display (the lift's display IS the cage floor) teleported
 * along the path every tick with a client-side teleport interpolation, so the motion is smooth and no vanilla
 * physics (rails, gravity, a player's WASD on a minecart) can fight it. The cart's minecart rides the display and
 * the player rides the minecart. A ride is one or more legs (the lift to the Dwarven Mines is a leg down each
 * island's shaft, with the island change in the dark between them).
 *
 * <p>Riders are untouchable (no mob target, no damage of any kind) and sneaking skips straight to the end (the
 * reference's travel is instant, so the wait is only ever a choice). A warp, death or logout mid-ride simply ends
 * it where it is. Every ride entity is non-persistent and tagged, and the module's disable sweeps them.
 */
@SuppressWarnings("removal")   // TeleportFlag.EntityState: still how Paper 26.2 keeps a teleported vehicle's passengers
final class Rides implements Listener {

    enum Kind { CART, LIFT }

    /** One stretch of a ride, in one world. */
    record Leg(String island, Kind kind, List<double[]> pts) {}

    private static final Set<UUID> RIDING = ConcurrentHashMap.newKeySet();

    /** True while the player sits in one of our vehicles (mobs, knives and blasts leave them alone). */
    static boolean riding(Player p) { return p != null && RIDING.contains(p.getUniqueId()); }

    private final MinesModule m;
    private final NamespacedKey tag;
    private final Map<UUID, Ride> rides = new HashMap<>();
    private BukkitTask task;

    private final class Ride {
        final UUID player;
        final List<Leg> legs;
        final Runnable arrive;
        int leg;
        double s, v;
        double[] cum;
        BlockDisplay base;
        Minecart cart;
        final List<Display> extras = new ArrayList<>();
        boolean ending;
        int idleTicks;

        Ride(UUID player, List<Leg> legs, Runnable arrive) { this.player = player; this.legs = legs; this.arrive = arrive; }

        Leg current() { return legs.get(leg); }
    }

    Rides(MinesModule m) {
        this.m = m;
        this.tag = new NamespacedKey(Sky.plugin(), "mines_ride");
    }

    void startTicking() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 1L, 1L);
    }

    int active() { return rides.size(); }

    List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Ride r : rides.values()) {
            Player p = Bukkit.getPlayer(r.player);
            out.add((p == null ? r.player.toString() : p.getName()) + " " + r.current().kind() + " leg " + (r.leg + 1) + "/" + r.legs.size()
                    + " " + Math.round(r.s) + "/" + Math.round(r.cum == null ? 0 : r.cum[r.cum.length - 1]) + " blocks");
        }
        return out;
    }

    // ---- start / stop ---------------------------------------------------------------------------------

    /**
     * Seat the player and start moving. {@code arrive} runs once the last leg ends (or the player sneaks to skip),
     * with the player already out of the vehicle: it puts them on the platform / landing. Returns false when a leg
     * has no usable path or its world is missing (the caller then falls back to a plain teleport).
     */
    boolean start(Player p, List<Leg> legs, Runnable arrive) {
        if (legs.isEmpty()) return false;
        for (Leg l : legs) if (l.pts().size() < 2 || Sky.worlds().world(l.island()) == null) return false;
        stop(p, false);
        Ride r = new Ride(p.getUniqueId(), legs, arrive);
        rides.put(r.player, r);
        RIDING.add(r.player);
        if (!board(r, p)) { abort(r); return false; }
        startTicking();
        return true;
    }

    /** End a player's ride now. {@code arrive}: put them where the ride was going (true) or leave them be. */
    void stop(Player p, boolean arrive) {
        Ride r = rides.get(p.getUniqueId());
        if (r == null) return;
        if (arrive) finish(r); else abort(r);
    }

    /** Remove every ride entity (module disable): riders are put at their destinations. */
    void shutdown() {
        for (Ride r : new ArrayList<>(rides.values())) finish(r);
        rides.clear();
        RIDING.clear();
        if (task != null) { task.cancel(); task = null; }
    }

    private boolean board(Ride r, Player p) {
        Leg l = r.current();
        World w = Sky.worlds().world(l.island());
        if (w == null) return false;
        List<double[]> pts = l.pts();
        r.cum = RailRoute.cumulative(pts);
        r.s = 0;
        r.v = 0;
        double[] at = RailRoute.at(pts, r.cum, 0);
        Location loc = new Location(w, at[0], at[1], at[2], yawOf(at[3], at[4]), 0f);
        p.closeInventory();
        p.setFallDistance(0);
        Location seat = loc.clone();
        seat.setYaw(p.getLocation().getYaw());
        seat.setPitch(p.getLocation().getPitch());
        if (p.getVehicle() != null) p.leaveVehicle();
        p.teleport(seat);
        r.base = w.spawn(loc, BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.setTeleportDuration(2);
            d.setViewRange(2f);
            d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            if (l.kind() == Kind.LIFT) {
                d.setBlock(Material.SPRUCE_PLANKS.createBlockData());
                d.setTransformation(new Transformation(new Vector3f(-2.5f, -0.25f, -2.5f), new AxisAngle4f(), new Vector3f(5f, 0.25f, 5f), new AxisAngle4f()));
            } else d.setBlock(Material.AIR.createBlockData());
        });
        if (l.kind() == Kind.LIFT) {
            // the cage's two chains, riding up the shaft with it (same interpolation, so they move as one)
            for (float[] c : new float[][]{{-2.5f, -2.5f}, {1.5f, 1.5f}})
                r.extras.add(w.spawn(loc, BlockDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setTeleportDuration(2);
                    d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
                    d.setBlock(Bukkit.createBlockData("minecraft:iron_chain[axis=y]"));
                    d.setTransformation(new Transformation(new Vector3f(c[0], 0f, c[1]), new AxisAngle4f(), new Vector3f(1f, 7f, 1f), new AxisAngle4f()));
                }));
        }
        Entity seatOn = r.base;
        if (l.kind() == Kind.CART) {
            try {
                r.cart = w.spawn(loc, Minecart.class, c -> {
                    c.setPersistent(false);
                    c.setGravity(false);
                    c.setInvulnerable(true);
                    c.setMaxSpeed(0);
                    c.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
                });
                if (r.base.addPassenger(r.cart)) seatOn = r.cart;
                else { r.cart.remove(); r.cart = null; }
            } catch (Throwable t) {
                if (r.cart != null) r.cart.remove();
                r.cart = null;
            }
        }
        return seatOn.addPassenger(p);
    }

    /** Take the player out of the current leg's vehicle and remove it. */
    private void unseat(Ride r) {
        r.ending = true;
        Player p = Bukkit.getPlayer(r.player);
        try {
            if (p != null && p.getVehicle() != null) p.leaveVehicle();
        } catch (Throwable ignored) { }
        for (Entity e : new Entity[]{r.cart, r.base}) if (e != null) try { e.eject(); e.remove(); } catch (Throwable ignored) { }
        for (Display d : r.extras) try { d.remove(); } catch (Throwable ignored) { }
        r.extras.clear();
        r.cart = null;
        r.base = null;
    }

    /** The ride ends where it was going: out of the vehicle, then {@code arrive}. */
    private void finish(Ride r) {
        if (!rides.containsKey(r.player)) return;
        unseat(r);
        rides.remove(r.player);
        RIDING.remove(r.player);
        Player p = Bukkit.getPlayer(r.player);
        if (p != null && p.isOnline() && r.arrive != null) {
            try { p.setFallDistance(0); r.arrive.run(); } catch (Throwable t) { Sky.log().warning("[mines] ride arrival failed: " + t); }
        }
    }

    /** The ride ends where it is (warp, death, logout): no arrival. */
    private void abort(Ride r) {
        unseat(r);
        rides.remove(r.player);
        RIDING.remove(r.player);
    }

    // ---- motion -------------------------------------------------------------------------------------

    private void tick() {
        if (rides.isEmpty()) return;
        for (Ride r : new ArrayList<>(rides.values())) {
            if (r.ending) continue;
            Player p = Bukkit.getPlayer(r.player);
            if (p == null || !p.isOnline() || p.isDead()) { abort(r); continue; }
            if (r.base == null || !r.base.isValid()) { finish(r); continue; }
            // the player was pulled off by something else (and the dismount was not ours): end it here
            Entity seat = r.cart != null ? r.cart : r.base;
            if (!seat.getPassengers().contains(p)) {
                if (++r.idleTicks > 3) { abort(r); continue; }
                seat.addPassenger(p);
            } else r.idleTicks = 0;
            Leg l = r.current();
            double total = r.cum[r.cum.length - 1];
            boolean lift = l.kind() == Kind.LIFT;
            double max = lift ? m.dials.liftSpeed.get() : m.dials.cartSpeed.get();
            double acc = lift ? 0.03 : 0.05;
            r.v = RailRoute.nextSpeed(r.v, Math.max(0.05, max), acc, total - r.s, 0.05);
            r.s = Math.min(total, r.s + r.v);
            double[] at = RailRoute.at(l.pts(), r.cum, r.s);
            Location loc = new Location(r.base.getWorld(), at[0], at[1], at[2], yawOf(at[3], at[4]), 0f);
            r.base.teleport(loc, TeleportFlag.EntityState.RETAIN_PASSENGERS);
            for (Display d : r.extras) d.teleport(loc);
            if (r.cart != null && (Math.abs(at[3]) + Math.abs(at[4])) > 1e-6) r.cart.setRotation((float) Math.toDegrees(Math.atan2(at[4], at[3])), 0f);
            if (lift && Bukkit.getCurrentTick() % 12 == 0) p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_CHAIN_STEP, 0.5f, 0.7f);
            if (!lift && r.v > 0.3 && Bukkit.getCurrentTick() % 20 == 0) p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_MINECART_INSIDE, 0.25f, 1f);
            if (r.s >= total - 1e-6) {
                if (r.leg + 1 < r.legs.size()) {
                    unseat(r);
                    r.ending = false;
                    r.leg++;
                    if (!board(r, p)) finish(r);
                } else finish(r);
            }
        }
    }

    static float yawOf(double dx, double dz) {
        if (Math.abs(dx) + Math.abs(dz) < 1e-9) return 0f;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    // ---- listeners ----------------------------------------------------------------------------------

    private boolean ours(Entity e) {
        return e != null && e.getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
    }

    /** Sneak = skip to the end. Any other dismount we did not cause (a warp, death) ends the ride where it is. */
    @EventHandler(priority = EventPriority.LOW)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Ride r = rides.get(p.getUniqueId());
        if (r == null || r.ending) return;
        if (p.isSneaking() && !p.isDead()) {
            e.setCancelled(true);
            r.ending = true;
            Msg.send(p, "&7You hop out and walk the rest of the way.");
            Bukkit.getScheduler().runTask(Sky.plugin(), () -> finish(r));
            return;
        }
        r.ending = true;
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> abort(r));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (ours(e.getEntity())) { e.setCancelled(true); return; }
        if (e.getEntity() instanceof Player p && riding(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSkyDamage(SkyEvents.Damage e) {
        if (riding(e.ctx.playerVictim())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && riding(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent e) { if (ours(e.getVehicle())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent e) { if (ours(e.getVehicle())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (!ours(e.getVehicle())) return;
        Ride r = e.getEntered() instanceof Player p ? rides.get(p.getUniqueId()) : null;
        if (r == null || r.cart != e.getVehicle()) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Ride r = rides.get(e.getPlayer().getUniqueId());
        if (r != null) abort(r);
    }
}
