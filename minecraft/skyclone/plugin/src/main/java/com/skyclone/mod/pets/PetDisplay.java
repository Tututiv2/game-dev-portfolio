package com.skyclone.mod.pets;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.pets.PetService.View;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The summoned pet floating at the owner's shoulder: an ItemDisplay of the pet's head, bobbing,
 * with a TextDisplay "&8[Lvl 23] &6Bee" above it. Non-persistent entities, re-made on world change
 * and on every pet change; one 2-tick task moves them all.
 */
final class PetDisplay {

    /** {@code box}: an Interaction hitbox riding along with a ridable pet, so the owner can right-click it (null otherwise). */
    private record Shown(ItemDisplay head, TextDisplay label, String key, org.bukkit.entity.Interaction box, boolean seated, boolean stays) {}

    private final PetsModule mod;
    private final Map<UUID, Shown> shown = new HashMap<>();
    /** Rock "Rolling Stone" (wave 5): where a pet that stays put was last left. Survives refreshes, not quits. */
    private final Map<UUID, Location> parked = new HashMap<>();
    private int tick;

    PetDisplay(PetsModule mod) { this.mod = mod; }

    void start() {
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::move, 20L, 2L);
    }

    /** Re-create (or remove) the player's floating pet to match their data. */
    void refresh(Player p) {
        remove(p.getUniqueId());
        if (!mod.dialShowPets() || !p.isOnline() || !Sky.profiles().isReady(p)) return;
        if (!setting(p, "pets.visible")) return;
        View v = mod.service().active(p);
        if (v == null) return;
        PetRide.Seat seat = mod.ride() == null ? null : mod.ride().seat(p);
        boolean seated = seat != null;
        float scale = seated ? 1.1f : 0.55f;
        boolean stays = !seated && staysPut(p);
        Location at = seated ? seatSpot(seat) : stays ? parkedSpot(p) : spot(p, 0);
        String key = v.owned().uuid + ":" + v.level() + ":" + v.tier();
        ItemDisplay head = p.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(Icons.skull(mod.service().skull(v), " ", List.of()));
            d.setBillboard(Display.Billboard.FIXED);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            d.setTeleportDuration(2);
            d.setShadowRadius(0f);
        });
        org.bukkit.entity.Interaction box = null;
        if (!seated && ((mod.ride() != null && mod.ride().rideFx(p) != null) || (mod.forms() != null && mod.forms().canMorph(p)))) {
            box = p.getWorld().spawn(at.clone().add(0, -0.3, 0), org.bukkit.entity.Interaction.class, b -> {
                b.setPersistent(false);
                b.setInteractionWidth(0.6f);
                b.setInteractionHeight(0.6f);
                b.setResponsive(true);
            });
        }
        TextDisplay label = p.getWorld().spawn(at.clone().add(0, 0.55, 0), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.text(Text.c("&8[Lvl " + v.level() + "] " + v.rarity().code() + v.def().name));
            d.setBillboard(Display.Billboard.CENTER);
            d.setSeeThrough(false);
            d.setShadowed(true);
            d.setDefaultBackground(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            d.setTeleportDuration(2);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.7f, 0.7f, 0.7f), new AxisAngle4f()));
        });
        shown.put(p.getUniqueId(), new Shown(head, label, key, box, seated, stays));
        applyHiding(p.getUniqueId(), head, label);
    }

    // ---- visibility -------------------------------------------------------------------------------
    //
    // Three switches decide who sees a floating pet:
    //   Pets menu "Hide Pets" (PetData.hidePets)      the viewer sees no pets at all, their own included
    //   Settings "Show Other Pets" (pets.others_visible) the viewer sees no OTHER player's pet
    //   Settings "Show My Pet" (pets.visible)          the owner's pet is not drawn for anyone
    // The two Settings keys are owned by the menus module and read from the PDC mirror it keeps
    // (namespace skyclone, key "setting.<key>"); unset = the default (true).

    static boolean setting(Player p, String key) {
        // wave 5: core's Features.Settings is the real door once menus provides it; the PDC mirror stays the fallback
        try {
            if (Sky.has(com.skyclone.api.feature.Features.Settings.class))
                return Sky.service(com.skyclone.api.feature.Features.Settings.class).flag(p, key, true);
        } catch (RuntimeException ignored) { }
        try {
            String v = p.getPersistentDataContainer().get(new org.bukkit.NamespacedKey("skyclone", "setting." + key),
                    org.bukkit.persistence.PersistentDataType.STRING);
            return !"false".equals(v);
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean hides(Player viewer, UUID owner) {
        PetData d = mod.service().data(viewer);
        if (d != null && d.hidePets) return true;
        return !viewer.getUniqueId().equals(owner) && !setting(viewer, "pets.others_visible");
    }

    private void applyHiding(UUID owner, Entity... es) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (hides(viewer, owner)) for (Entity e : es) viewer.hideEntity(Sky.plugin(), e);
        }
    }

    /** A viewer toggled "Hide Pets" / "Show Other Pets": update what they see. */
    void viewerChanged(Player viewer) {
        for (Map.Entry<UUID, Shown> en : shown.entrySet()) {
            Shown s = en.getValue();
            boolean hide = hides(viewer, en.getKey());
            for (Entity e : new Entity[]{s.head(), s.label()}) {
                if (e == null || !e.isValid()) continue;
                if (hide) viewer.hideEntity(Sky.plugin(), e); else viewer.showEntity(Sky.plugin(), e);
            }
        }
    }

    /** Last seen Settings state per player: bit 0 = own pet shown, bit 1 = other pets shown. */
    private final Map<UUID, Integer> settingState = new HashMap<>();

    /** Settings has no change event: poll the two keys once a second and react to a flip. */
    private void pollSettings() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            int now = (setting(p, "pets.visible") ? 1 : 0) | (setting(p, "pets.others_visible") ? 2 : 0);
            Integer was = settingState.put(p.getUniqueId(), now);
            if (was == null || was == now) continue;
            if ((was & 1) != (now & 1)) refresh(p);
            if ((was & 2) != (now & 2)) viewerChanged(p);
        }
        settingState.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    void remove(UUID id) {
        Shown s = shown.remove(id);
        if (s == null) return;
        if (s.head() != null) s.head().remove();
        if (s.label() != null) s.label().remove();
        if (s.box() != null) s.box().remove();
    }

    /** Whose ridable pet this hitbox belongs to, or null when it is not one of ours. */
    UUID ownerOfBox(org.bukkit.entity.Interaction box) {
        for (Map.Entry<UUID, Shown> e : shown.entrySet()) if (box.equals(e.getValue().box())) return e.getKey();
        return null;
    }

    /** Where the pet's head sits while ridden: under the rider (Rock) or as the mount's body (Armadillo). */
    private static Location seatSpot(PetRide.Seat seat) {
        Location l = seat.vehicle().getLocation();
        Location at = l.clone().add(0, "mount".equals(seat.mode()) ? 0.75 : -0.22, 0);
        at.setPitch(0);
        return at;
    }

    // ---- a pet that stays where you leave it (Rock "Rolling Stone") ------------------------------------

    /** True when the summoned pet does not follow you (it has the {@code sail} effect). */
    private boolean staysPut(Player p) { return mod.forms() != null && mod.forms().fx(p, "sail") != null; }

    /** Where the player's pet is parked, or null when it follows them. */
    Location parkedAt(Player p) {
        Location l = parked.get(p.getUniqueId());
        return l == null ? null : l.clone();
    }

    /** Move a pet that stays put to this spot (the player's feet). */
    void park(Player p, Location feet) {
        Location l = feet.clone();
        l.setPitch(0);
        parked.put(p.getUniqueId(), l);
        refresh(p);
    }

    /** The parked spot to draw at: re-parked at the player's feet when it is unset, in another world or far behind. */
    private Location parkedSpot(Player p) {
        Location l = parked.get(p.getUniqueId());
        if (l == null || l.getWorld() != p.getWorld() || l.distanceSquared(p.getLocation()) > 48 * 48) {
            l = p.getLocation().clone();
            l.setPitch(0);
            parked.put(p.getUniqueId(), l);
        }
        return l.clone().add(0, 0.3, 0);
    }

    /** The player left: forget everything kept for them across refreshes. */
    void forget(UUID id) {
        remove(id);
        parked.remove(id);
    }

    void removeAll() {
        for (UUID id : List.copyOf(shown.keySet())) remove(id);
    }

    private static Location spot(Player p, double bob) {
        Location l = p.getLocation();
        Vector back = l.getDirection().setY(0);
        if (back.lengthSquared() < 1e-6) back = new Vector(0, 0, 1);
        back.normalize();
        Vector side = new Vector(-back.getZ(), 0, back.getX());   // to the player's left
        Location at = l.clone().add(side.multiply(0.75)).add(back.multiply(-0.45)).add(0, 1.9 + bob, 0);
        at.setYaw(l.getYaw());
        at.setPitch(0);
        return at;
    }

    private void move() {
        tick++;
        if (tick % 10 == 0) pollSettings();
        for (Map.Entry<UUID, Shown> e : List.copyOf(shown.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            Shown s = e.getValue();
            if (p == null || !p.isOnline()) { remove(e.getKey()); continue; }
            if (s.head() == null || !s.head().isValid() || s.head().getWorld() != p.getWorld()
                    || s.head().getLocation().distanceSquared(p.getLocation()) > (s.stays() ? 48 * 48 : 64 * 64)) {
                refresh(p);
                continue;
            }
            if (mod.ride() != null) mod.ride().tick(p);
            PetRide.Seat seat = mod.ride() == null ? null : mod.ride().seat(p);
            if ((seat != null) != s.seated() || (seat == null && staysPut(p) != s.stays())) { refresh(p); continue; }
            if (s.stays()) continue;   // a parked pet stays where it was left
            if (seat != null) {
                Location at = seatSpot(seat);
                s.head().teleport(at);
                s.label().teleport(at.clone().add(0, "mount".equals(seat.mode()) ? 2.3 : 2.4, 0));
                continue;
            }
            double bob = Math.sin((tick + (e.getKey().hashCode() & 31)) * 0.18) * 0.12;
            Location at = spot(p, bob);
            s.head().teleport(at);
            s.label().teleport(at.clone().add(0, 0.5, 0));
            if (s.box() != null && s.box().isValid()) s.box().teleport(at.clone().add(0, -0.3, 0));
        }
    }
}
