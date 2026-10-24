package com.skyclone.mod.pets;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.mod.pets.PetService.View;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Riding your pet (Rock "Boulder Seat", Armadillo "Saddle Up"): the {@code ride} effect.
 * <ul>
 *   <li>{@code mode: "sit"} (Rock): a seat where you stand. The rock does not move; "while sitting on
 *       your rock" perks read the {@code riding} condition.</li>
 *   <li>{@code mode: "mount"} (Armadillo): an invisible, saddled horse you steer, its speed from your
 *       Speed stat. The pet's head is drawn on it.</li>
 * </ul>
 * Start: right-click your floating pet (an Interaction box rides along with it), {@code /pet ride}, or the
 * Pets menu. Stop: sneak (vanilla dismount). Seats are non-persistent and removed on every exit path:
 * dismount, despawn / pet change, world change, teleport, quit, disable.
 */
final class PetRide implements Listener {

    record Seat(Entity vehicle, String mode) {}

    private final PetsModule mod;
    private final Map<UUID, Seat> seats = new HashMap<>();

    PetRide(PetsModule mod) { this.mod = mod; }

    void register() { Bukkit.getPluginManager().registerEvents(this, Sky.plugin()); }

    /** The "ride" effect of the summoned pet, or null when it cannot be ridden. */
    JsonObject rideFx(Player p) {
        View v = mod.service().active(p);
        if (v == null || v.t() == null) return null;
        for (PetDefs.Ability a : v.t().abilities) for (JsonObject fx : a.fx) if ("ride".equals(PetService.str(fx, "t"))) return fx;
        return null;
    }

    boolean riding(Player p) {
        Seat s = seats.get(p.getUniqueId());
        return s != null && s.vehicle().isValid() && s.vehicle().getPassengers().contains(p);
    }

    Seat seat(Player p) { return seats.get(p.getUniqueId()); }

    /** Toggle: get on the summoned pet, or off it. */
    void toggle(Player p) {
        if (seats.containsKey(p.getUniqueId())) { stop(p, true); return; }
        JsonObject fx = rideFx(p);
        if (fx == null) {
            Msg.error(p, mod.service().active(p) == null ? "You need to summon a pet first!" : "Your summoned pet can't be ridden.");
            return;
        }
        if (p.isInsideVehicle()) { Msg.error(p, "You are already riding something."); return; }
        if (!p.isOnGround() && !p.isInWater()) { Msg.error(p, "Stand on solid ground first."); return; }
        String mode = PetService.str(fx, "mode");
        Location at = p.getLocation();
        // a Rock that stays put (wave 5 "Rolling Stone"): you sit where the rock IS, when it is close enough to climb on
        Location rock = "mount".equals(mode) ? null : mod.display().parkedAt(p);
        if (rock != null && rock.getWorld() == p.getWorld() && rock.distanceSquared(at) <= 6 * 6) {
            rock.setYaw(at.getYaw());
            at = rock;
        }
        Entity vehicle;
        if ("mount".equals(mode)) {
            vehicle = p.getWorld().spawn(at, Horse.class, h -> {
                h.setPersistent(false);
                h.setInvisible(true);
                h.setSilent(true);
                h.setInvulnerable(true);
                h.setTamed(true);
                h.setOwner(p);
                h.setAdult();
                h.setAgeLock(true);
                h.setJumpStrength(0.6);
                h.getInventory().setSaddle(new ItemStack(Material.SADDLE));
                h.setRemoveWhenFarAway(false);
                h.setCollidable(false);
            });
            applySpeed(p, (Horse) vehicle, fx);
        } else {
            // a marker stand half a block up: you sit ON the rock, whose head is drawn just under the seat
            vehicle = p.getWorld().spawn(at.clone().add(0, 0.5, 0), ArmorStand.class, a -> {
                a.setPersistent(false);
                a.setInvisible(true);
                a.setGravity(false);
                a.setInvulnerable(true);
                a.setSilent(true);
                a.setSmall(true);
                a.setMarker(true);
                a.setBasePlate(false);
            });
        }
        vehicle.addPassenger(p);
        seats.put(p.getUniqueId(), new Seat(vehicle, mode == null ? "sit" : mode));
        Msg.sound(p, Sound.ENTITY_HORSE_SADDLE, 1.2f);
        Msg.send(p, "&aYou are riding your " + mod.service().active(p).def().name + "&a. &7Sneak to get off.");
        mod.effects().invalidate(p);
        Sky.stats().invalidate(p);
        mod.display().refresh(p);
    }

    /**
     * The mount's speed follows the rider's Speed stat (100 Speed = a walking player's 0.1) and the pet's
     * level: your own walking pace at level 1, up to the data's {@code speed} multiplier (1.6) at level 100,
     * never past {@code pets.ride-max-speed} blocks/s ({@link PetMath#mountAttr}).
     */
    private void applySpeed(Player p, Horse h, JsonObject fx) {
        AttributeInstance a = h.getAttribute(Attribute.MOVEMENT_SPEED);
        if (a == null) return;
        PetService.View v = mod.service().active(p);
        int level = v == null ? 1 : v.level();
        double mult = fx.has("speed") ? fx.get("speed").getAsDouble() : 1.6;
        a.setBaseValue(PetMath.mountAttr(Sky.stats().get(p, Stat.SPEED), level, mult,
                mod.dialSpeedCap(), mod.dialRideMaxSpeed()));
    }

    void stop(Player p, boolean tell) {
        Seat s = seats.remove(p.getUniqueId());
        if (s == null) return;
        Entity v = s.vehicle();
        if (v.isValid() && "sit".equals(s.mode()) && p.isOnline() && mod.forms() != null && mod.forms().fx(p, "sail") != null) {
            mod.display().park(p, v.getLocation().clone().add(0, -0.5, 0));   // the rock stays where you got off it
        }
        if (v.isValid()) {
            v.eject();
            v.remove();
        }
        if (tell && p.isOnline()) Msg.send(p, "&7You got off your pet.");
        if (p.isOnline()) {
            mod.effects().invalidate(p);
            Sky.stats().invalidate(p);
            mod.display().refresh(p);
        }
    }

    void stopAll() {
        for (UUID id : List.copyOf(seats.keySet())) {
            Player p = Bukkit.getPlayer(id);
            Seat s = seats.remove(id);
            if (s != null && s.vehicle().isValid()) { s.vehicle().eject(); s.vehicle().remove(); }
            if (p != null) mod.display().refresh(p);
        }
    }

    /** Pet changed (summon, despawn, level-up refresh): a seat only lives while that pet can still be ridden. */
    void check(Player p) {
        if (seats.containsKey(p.getUniqueId()) && rideFx(p) == null) stop(p, false);
    }

    /** Every 2 ticks from the display loop: keep the mount's speed fresh, drop dead seats. */
    void tick(Player p) {
        Seat s = seats.get(p.getUniqueId());
        if (s == null) return;
        if (!s.vehicle().isValid() || !s.vehicle().getPassengers().contains(p)) { stop(p, false); return; }
        if (s.vehicle() instanceof Horse h && h.getTicksLived() % 40 == 0) {
            JsonObject fx = rideFx(p);
            if (fx != null) applySpeed(p, h, fx);
        }
    }

    // ---- triggers and exit paths -------------------------------------------------------------

    /** Right-click the floating pet's Interaction box (only the owner's counts). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClickPet(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction box)) return;
        UUID owner = mod.display().ownerOfBox(box);
        if (owner == null) return;
        e.setCancelled(true);
        if (!owner.equals(e.getPlayer().getUniqueId()) || !mod.rateOk(e.getPlayer())) return;
        if (rideFx(e.getPlayer()) == null && mod.forms() != null && mod.forms().canMorph(e.getPlayer())) {
            mod.forms().toggleMorph(e.getPlayer());   // Rat "Shapeshift": right-click your pet to take its form
            return;
        }
        toggle(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Seat s = seats.get(p.getUniqueId());
        if (s != null && s.vehicle().equals(e.getDismounted())) {
            Bukkit.getScheduler().runTask(Sky.plugin(), () -> stop(p, true));
        }
    }

    /** Mounts are invulnerable, but fall damage and suffocation are still worth cancelling outright. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMountHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        for (Seat s : seats.values()) if (s.vehicle().equals(le)) { e.setCancelled(true); return; }
    }

    /**
     * The mount's saddle is not loot. Its screen still OPENS: a rider's client sends "open inventory"
     * to the server instead of opening its own on E, so cancelling the open (the first version) left a
     * player on an Armadillo with no inventory at all. The horse screen shows the player's inventory
     * too; every click that could reach the mount's own slots (saddle, armour) is refused instead.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMountClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!isMountView(e.getView().getTopInventory())) return;
        int top = e.getView().getTopInventory().getSize();
        if (e.getRawSlot() < top || e.isShiftClick()
                || e.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMountDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (!isMountView(e.getView().getTopInventory())) return;
        int top = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) if (raw < top) { e.setCancelled(true); return; }
    }

    private boolean isMountView(org.bukkit.inventory.Inventory top) {
        if (top == null || !(top.getHolder() instanceof Horse h)) return false;
        for (Seat s : seats.values()) if (s.vehicle().equals(h)) return true;
        return false;
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { stop(e.getPlayer(), false); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.DISMOUNT) return;
        if (seats.containsKey(e.getPlayer().getUniqueId())) stop(e.getPlayer(), false);
    }
}
