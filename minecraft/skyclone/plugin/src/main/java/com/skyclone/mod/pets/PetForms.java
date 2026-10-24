package com.skyclone.mod.pets;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.pets.PetService.View;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pet abilities that change YOU or the world around you rather than a number (wave 5):
 * <ul>
 *   <li>Rat "Shapeshift" ({@code morph}): take the rat's form (you shrink to {@code scale});
 *       "Turbo Tail" ({@code morph_speed}): in rat form you run {@code mult}x as fast and may fly.</li>
 *   <li>Rat "Cheese Nose" ({@code cheese}): every {@code every} s a piece of cheese turns up within {@code radius}
 *       blocks, seen only by you, with a scent trail; walk over it. "Rodent Blessing" ({@code rat_blessing}):
 *       finding it blesses a random player near you with Magic Find (PetEffects.bless).</li>
 *   <li>Rock "Rolling Stone" ({@code sail}): your rock stays where it lands; sneak to call it (PetDisplay parks it).</li>
 *   <li>Armadillo "Burrower" / "Efficient Gait" ({@code tunnel} / {@code tunnel_energy}): dig while riding it.</li>
 * </ul>
 * Everything here is undone on every exit path: toggle, pet change, world change, quit, disable.
 */
final class PetForms implements Listener {

    private record Cheese(ItemDisplay entity, Location at, long until) {}

    private final PetsModule mod;
    private final Set<UUID> morphed = new HashSet<>();
    private final Set<UUID> grantedFlight = new HashSet<>();
    private final Map<UUID, Cheese> cheese = new HashMap<>();
    private final Map<UUID, Long> nextCheese = new HashMap<>();
    private final Map<UUID, Long> sailCd = new HashMap<>();
    private NamespacedKey scaleKey, speedKey;

    PetForms(PetsModule mod) { this.mod = mod; }

    void register() {
        scaleKey = new NamespacedKey(Sky.plugin(), "pet_morph_scale");
        speedKey = new NamespacedKey(Sky.plugin(), "pet_morph_speed");
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 40L, 10L);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tunnelTick, 45L, 4L);   // mining drops a dig after 6 ticks without a swing
    }

    /** The summoned pet's first effect of this type, or null. */
    JsonObject fx(Player p, String type) {
        View v = mod.service().active(p);
        if (v == null || v.t() == null) return null;
        for (PetDefs.Ability a : v.t().abilities) for (JsonObject f : a.fx) if (type.equals(PetService.str(f, "t"))) return f;
        return null;
    }

    private static double num(JsonObject f, String k, double def) {
        try { return f != null && f.has(k) ? f.get(k).getAsDouble() : def; } catch (RuntimeException e) { return def; }
    }

    // ---- morph -----------------------------------------------------------------------------------

    boolean canMorph(Player p) { return fx(p, "morph") != null; }

    boolean morphed(Player p) { return morphed.contains(p.getUniqueId()); }

    void toggleMorph(Player p) {
        if (morphed(p)) { unmorph(p, true); return; }
        JsonObject f = fx(p, "morph");
        if (f == null) {
            Msg.error(p, mod.service().active(p) == null ? "You need to summon a pet first!" : "Your summoned pet has no form to take.");
            return;
        }
        if (mod.ride() != null && mod.ride().riding(p)) mod.ride().stop(p, false);
        double scale = Math.max(0.1, Math.min(1, num(f, "scale", 0.3)));
        setModifier(p, Attribute.SCALE, scaleKey, scale - 1, AttributeModifier.Operation.ADD_NUMBER);
        JsonObject sp = fx(p, "morph_speed");
        if (sp != null) {
            setModifier(p, Attribute.MOVEMENT_SPEED, speedKey, Math.max(0, num(sp, "mult", 2) - 1), AttributeModifier.Operation.MULTIPLY_SCALAR_1);
            boolean fly = !sp.has("fly") || sp.get("fly").getAsBoolean();
            if (fly && !p.getAllowFlight() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)) {
                p.setAllowFlight(true);
                grantedFlight.add(p.getUniqueId());
            }
        }
        morphed.add(p.getUniqueId());
        p.getWorld().spawnParticle(Particle.POOF, p.getLocation().add(0, 0.5, 0), 25, 0.3, 0.4, 0.3, 0.02);
        Msg.sound(p, Sound.ENTITY_SILVERFISH_AMBIENT, 1.4f);
        View v = mod.service().active(p);
        Msg.send(p, "&aYou took the form of your " + (v == null ? "pet" : v.def().name) + "&a! &7Do it again to change back.");
    }

    void unmorph(Player p, boolean tell) {
        if (!morphed.remove(p.getUniqueId())) return;
        removeModifier(p, Attribute.SCALE, scaleKey);
        removeModifier(p, Attribute.MOVEMENT_SPEED, speedKey);
        if (grantedFlight.remove(p.getUniqueId()) && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        if (tell && p.isOnline()) {
            p.getWorld().spawnParticle(Particle.POOF, p.getLocation().add(0, 0.8, 0), 25, 0.3, 0.6, 0.3, 0.02);
            Msg.send(p, "&7You changed back.");
        }
    }

    private static void setModifier(Player p, Attribute attr, NamespacedKey key, double amount, AttributeModifier.Operation op) {
        try {
            AttributeInstance a = p.getAttribute(attr);
            if (a == null) return;
            a.removeModifier(key);
            a.addModifier(new AttributeModifier(key, amount, op, EquipmentSlotGroup.ANY));
        } catch (RuntimeException ignored) { }
    }

    private static void removeModifier(Player p, Attribute attr, NamespacedKey key) {
        try {
            AttributeInstance a = p.getAttribute(attr);
            if (a != null && key != null) a.removeModifier(key);
        } catch (RuntimeException ignored) { }
    }

    /** The pet changed: a form only lasts while that pet can still give it; a parked rock forgets its spot. */
    void check(Player p) {
        if (morphed(p) && fx(p, "morph") == null) unmorph(p, true);
        if (fx(p, "cheese") == null) clearCheese(p.getUniqueId());
    }

    // ---- cheese ----------------------------------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            JsonObject f = fx(p, "cheese");
            UUID id = p.getUniqueId();
            if (f == null) { clearCheese(id); continue; }
            Cheese c = cheese.get(id);
            if (c != null) {
                if (!c.entity().isValid() || c.at().getWorld() != p.getWorld() || now > c.until()) {
                    clearCheese(id);
                    nextCheese.put(id, now + (long) (num(f, "every", 90) * 1000));
                    continue;
                }
                if (p.getLocation().distanceSquared(c.at()) <= 1.7 * 1.7) { found(p, c); continue; }
                scent(p, c.at());
                continue;
            }
            if (now < nextCheese.computeIfAbsent(id, k -> now + (long) (num(f, "every", 90) * 1000))) continue;
            if (!cheeseAllowed(p)) continue;
            spawnCheese(p, f, now);
        }
        cheese.keySet().removeIf(u -> Bukkit.getPlayer(u) == null && clearCheeseQuiet(u));
    }

    /** On a public island, out of dungeons. */
    private static boolean cheeseAllowed(Player p) {
        String island = PetService.island(p);
        if (island == null || "private_island".equals(island) || "garden".equals(island)) return false;
        return !Sky.service(Features.Dungeons.class).inDungeon(p);
    }

    /** Force a piece of cheese next to the player (admin test path). */
    boolean forceCheese(Player p) {
        JsonObject f = fx(p, "cheese");
        if (f == null) return false;
        clearCheese(p.getUniqueId());
        return spawnCheese(p, f, System.currentTimeMillis());
    }

    private boolean spawnCheese(Player p, JsonObject f, long now) {
        double radius = Math.max(3, num(f, "radius", 12));
        Location at = null;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int tries = 0; tries < 12 && at == null; tries++) {
            double a = r.nextDouble(Math.PI * 2), d = r.nextDouble(3, radius);
            at = ground(p.getLocation().clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
        }
        UUID id = p.getUniqueId();
        nextCheese.put(id, now + (long) (num(f, "every", 90) * 1000));
        if (at == null) return false;
        Location spot = at;
        ItemDisplay e = p.getWorld().spawn(spot.clone().add(0, 0.25, 0), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.setItemStack(new ItemStack(Material.SPONGE));
            d.setBillboard(Display.Billboard.FIXED);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f((float) (Math.PI / 6), 0, 1, 0),
                    new Vector3f(0.35f, 0.3f, 0.35f), new AxisAngle4f()));
            d.setGlowing(true);
        });
        p.showEntity(Sky.plugin(), e);
        cheese.put(id, new Cheese(e, spot, now + (long) (num(f, "life", 60) * 1000)));
        p.sendActionBar(Text.c("&eYou smell cheese nearby..."));
        return true;
    }

    /** The first standable spot at or near this column (a solid block with two air blocks above), or null. */
    private static Location ground(Location l) {
        Block b = l.getBlock();
        for (int dy = 4; dy >= -6; dy--) {
            Block feet = b.getRelative(0, dy, 0);
            Block below = feet.getRelative(0, -1, 0);
            if (below.getType().isSolid() && feet.getType().isAir() && feet.getRelative(0, 1, 0).getType().isAir())
                return feet.getLocation().add(0.5, 0, 0.5);
        }
        return null;
    }

    /** A short trail of particles from the player toward the cheese, shown only to them. */
    private static void scent(Player p, Location at) {
        Location from = p.getLocation().add(0, 1.0, 0);
        Vector dir = at.toVector().add(new Vector(0, 0.3, 0)).subtract(from.toVector());
        double len = dir.length();
        if (len < 0.5) return;
        dir.normalize();
        for (int i = 1; i <= 4; i++) {
            double d = Math.min(len, i * 0.9);
            p.spawnParticle(Particle.HAPPY_VILLAGER, from.clone().add(dir.clone().multiply(d)), 1, 0.05, 0.05, 0.05, 0);
        }
        p.spawnParticle(Particle.WAX_ON, at.clone().add(0, 0.5, 0), 3, 0.2, 0.2, 0.2, 0);
    }

    private void found(Player p, Cheese c) {
        clearCheese(p.getUniqueId());
        p.getWorld().spawnParticle(Particle.ITEM, c.at().clone().add(0, 0.4, 0), 20, 0.2, 0.2, 0.2, 0.05, new ItemStack(Material.SPONGE));
        Msg.sound(p, Sound.ENTITY_GENERIC_EAT, 1.3f);
        Msg.send(p, "&eYou found a piece of cheese! &7Yummy!");
        JsonObject b = fx(p, "rat_blessing");
        if (b == null) return;
        View v = mod.service().active(p);
        PetDefs.Ability ab = null;
        if (v != null) for (PetDefs.Ability a : v.t().abilities) for (JsonObject f : a.fx) if (f == b) ab = a;
        double mf = v == null ? 0 : v.num(b.has("mf") ? b.get("mf").getAsString() : "a", ab);
        double secs = v == null ? 0 : v.num(b.has("dur") ? b.get("dur").getAsString() : "b", ab);
        if (mf <= 0 || secs <= 0) return;
        double radius = num(b, "radius", 30);
        List<Player> near = new ArrayList<>();
        for (Player o : p.getWorld().getPlayers()) {
            if (Sky.profiles().isReady(o) && o.getLocation().distanceSquared(p.getLocation()) <= radius * radius) near.add(o);
        }
        if (near.isEmpty()) return;
        Player lucky = near.get(ThreadLocalRandom.current().nextInt(near.size()));
        mod.effects().bless(lucky, mf, (long) (secs * 1000), p.getUniqueId(), num(b, "copy", 20));
        Msg.send(lucky, "&eRodent Blessing! &7" + (lucky == p ? "Your rat" : p.getName() + "'s rat") + " blessed you with &b+"
                + PetMath.fmt(mf) + " Magic Find &7for &a" + PetMath.fmt(secs) + "s&7.");
        if (lucky != p) Msg.send(p, "&eRodent Blessing &7went to &a" + lucky.getName() + "&7. Their rare drops may come to you too!");
    }

    private void clearCheese(UUID id) { clearCheeseQuiet(id); }

    private boolean clearCheeseQuiet(UUID id) {
        Cheese c = cheese.remove(id);
        if (c != null && c.entity().isValid()) c.entity().remove();
        return false;
    }

    // ---- Rock: call it to you --------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        if (!e.isSneaking() || !Sky.profiles().isReady(p)) return;
        JsonObject f = fx(p, "sail");
        if (f == null || (mod.ride() != null && mod.ride().riding(p))) return;
        long now = System.currentTimeMillis();
        Long ready = sailCd.get(p.getUniqueId());
        Location parked = mod.display().parkedAt(p);
        if (parked != null && parked.getWorld() == p.getWorld() && parked.distanceSquared(p.getLocation()) < 2.0 * 2.0) return;
        if (ready != null && now < ready) {
            p.sendActionBar(Text.c("&cYour rock needs &e" + ((ready - now) / 1000 + 1) + "s &cto roll again."));
            return;
        }
        sailCd.put(p.getUniqueId(), now + (long) (num(f, "cd", 15) * 1000));
        mod.display().park(p, p.getLocation());
        Msg.sound(p, Sound.BLOCK_STONE_PLACE, 0.8f);
        p.sendActionBar(Text.c("&7Your rock rolls over to you."));
    }

    // ---- Armadillo: dig through rock while riding (wave 5) ------------------------------------------
    //
    // "Burrower" ({@code tunnel}): riding your Armadillo on one of {@code islands} while pressing forward, the pet
    // SWINGS FOR YOU at the block you are looking at when that block is right in front of it: every pass fires a
    // BlockDamageEvent, which is exactly what mining's own dig engine listens to (start a dig / keep it alive), so
    // the block breaks at YOUR Mining Speed with YOUR held tool, and mining decides what may break (breaking power,
    // protection, ore areas) and pays drops, powder and XP. (Player#breakBlock was not an option: with mining's dig
    // engine on, a plain BlockBreakEvent is deliberately ignored.) Each block that actually broke costs energy
    // ({@code energy} max, {@code cost} each, refilling {@code regen} per pass while not digging); "Efficient Gait"
    // ({@code tunnel_energy}) makes each block cost a% less. // TUNE: the reference shows no energy numbers.

    private final Map<UUID, Double> energy = new HashMap<>();
    /** The block the pet is swinging at for each rider (to count it once it breaks). */
    private final Map<UUID, Dug> digging = new HashMap<>();
    /** A block being dug and what it was: when its type changes (air, mining's bedrock placeholder...) it broke. */
    private record Dug(Block block, Material was) {}

    /** A number of the summoned pet's ability that owns {@code f}: token {@code key} ("a") or a literal, else def. */
    private double fxNum(Player p, JsonObject f, String key, double def) {
        if (f == null || !f.has(key)) return def;
        View v = mod.service().active(p);
        if (v == null) return def;
        PetDefs.Ability ab = null;
        for (PetDefs.Ability a : v.t().abilities) for (JsonObject x : a.fx) if (x == f) ab = a;
        return v.num(f.get(key).getAsString(), ab);
    }

    private void tunnelTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!Sky.profiles().isReady(p)) continue;
            JsonObject f = fx(p, "tunnel");
            if (f == null) { energy.remove(id); continue; }
            double max = Math.max(1, num(f, "energy", 100));
            double e = energy.getOrDefault(id, max);
            int dug = 0;
            PetRide.Seat seat = mod.ride() == null ? null : mod.ride().seat(p);
            boolean onIsland = tunnelIsland(p, f);
            Dug was = digging.get(id);
            if (was != null && was.block().getType() != was.was()) { dug = 1; digging.remove(id); }   // it broke
            if (seat != null && "mount".equals(seat.mode()) && onIsland && e >= num(f, "cost", 1) && forward(p)) swing(p, seat.vehicle());
            else digging.remove(id);
            double saving = fxNum(p, fx(p, "tunnel_energy"), "v", 0);
            double next = PetMath.tunnelEnergy(e, dug, num(f, "cost", 1), saving, num(f, "regen", 2), max);
            energy.put(id, next);
            if (seat != null && "mount".equals(seat.mode()) && onIsland && (dug > 0 || next < max))
                p.sendActionBar(Text.c("&6Burrowing Energy: " + (next < 10 ? "&c" : "&e") + (int) next + "&6/" + (int) max));
        }
        energy.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
        digging.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
    }

    private static boolean forward(Player p) {
        try { return p.getCurrentInput().isForward(); } catch (Throwable t) { return true; }
    }

    private static boolean tunnelIsland(Player p, JsonObject f) {
        String island = PetService.island(p);
        if (island == null) return false;
        if (!f.has("islands")) return "crystal_hollows".equals(island);
        for (com.google.gson.JsonElement e : f.getAsJsonArray("islands")) if (island.equals(e.getAsString())) return true;
        return false;
    }

    /** Swing at the block the rider looks at, when it is within 3 blocks of the mount: mining's dig engine does the rest. */
    private void swing(Player p, org.bukkit.entity.Entity mount) {
        org.bukkit.util.RayTraceResult hit = p.rayTraceBlocks(5.0, org.bukkit.FluidCollisionMode.NEVER);
        Block b = hit == null ? null : hit.getHitBlock();
        if (b == null || b.getType() == Material.BEDROCK || b.getType() == Material.BARRIER) { digging.remove(p.getUniqueId()); return; }
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        if (c.getWorld() != mount.getWorld() || c.distanceSquared(mount.getLocation().add(0, 0.8, 0)) > 3.0 * 3.0) { digging.remove(p.getUniqueId()); return; }
        org.bukkit.event.block.BlockDamageEvent ev = new org.bukkit.event.block.BlockDamageEvent(p, b,
                hit.getHitBlockFace() == null ? org.bukkit.block.BlockFace.UP : hit.getHitBlockFace(), p.getInventory().getItemInMainHand(), false);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) { digging.remove(p.getUniqueId()); return; }
        Dug cur = digging.get(p.getUniqueId());
        if (cur == null || !cur.block().equals(b)) digging.put(p.getUniqueId(), new Dug(b, b.getType()));
        p.swingMainHand();
    }

    /** Admin: the player's Burrower state. */
    String tunnelStatus(Player p) {
        JsonObject f = fx(p, "tunnel");
        if (f == null) return p.getName() + "'s summoned pet cannot burrow";
        PetRide.Seat seat = mod.ride() == null ? null : mod.ride().seat(p);
        return p.getName() + ": energy " + PetMath.fmt(energy.getOrDefault(p.getUniqueId(), num(f, "energy", 100))) + "/" + PetMath.fmt(num(f, "energy", 100))
                + ", riding " + (seat != null && "mount".equals(seat.mode())) + ", island ok " + tunnelIsland(p, f)
                + ", Efficient Gait -" + PetMath.fmt(fxNum(p, fx(p, "tunnel_energy"), "v", 0)) + "% per block";
    }

    // ---- exit paths ------------------------------------------------------------------------------

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        clearCheese(e.getPlayer().getUniqueId());
        unmorph(e.getPlayer(), false);
    }

    void forget(Player p) {
        unmorph(p, false);
        clearCheese(p.getUniqueId());
        nextCheese.remove(p.getUniqueId());
        sailCd.remove(p.getUniqueId());
        energy.remove(p.getUniqueId());
        digging.remove(p.getUniqueId());
    }

    /** Profile ready: drop any form modifier a crash left on the player (vanilla saves attribute modifiers). */
    void sanitize(Player p) {
        if (morphed(p)) return;
        removeModifier(p, Attribute.SCALE, scaleKey);
        removeModifier(p, Attribute.MOVEMENT_SPEED, speedKey);
    }

    void stopAll() {
        for (Player p : Bukkit.getOnlinePlayers()) unmorph(p, false);
        for (UUID id : List.copyOf(cheese.keySet())) clearCheese(id);
    }
}
