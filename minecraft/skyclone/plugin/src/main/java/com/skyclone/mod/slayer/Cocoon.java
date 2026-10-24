package com.skyclone.mod.slayer;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Primordial belt, "Forced Metamorphosis" (NEU lore): a slain slayer boss or slayer miniboss has a 10% chance
 * (+ the Hunting attribute Cocoon Chance) to be wrapped in a cocoon that quickly hatches into the same monster, which
 * can be slain again. A hatched miniboss drops its own loot through core like any kill; a hatched BOSS is a plain
 * owned mob (no abilities, no timer, no quest) whose death re-rolls the boss loot ({@link SlayerLoot#rollCocoon}).
 * A hatched monster never cocoons again.
 */
final class Cocoon {

    static final String BELT = "THE_PRIMORDIAL";

    /** A hatched monster: whose, which slayer and tier, whether it is a boss, and when it crumbles. */
    record Hatch(UUID owner, String type, int tier, boolean boss, long expires) { }

    private final SlayerModule mod;
    private final Map<UUID, Hatch> hatched = new HashMap<>();
    private final List<Entity> shells = new ArrayList<>();
    /** Times a cocoon happened since boot (admin info / self-test). */
    int cocoons;

    Cocoon(SlayerModule mod) { this.mod = mod; }

    /** Is the player wearing (equipment) or carrying (accessory bag / inventory) The Primordial? */
    static boolean wearing(Player p) {
        try {
            if (Sky.service(Features.Accessories.class).has(p, BELT)) return true;
        } catch (RuntimeException ignored) { }
        try {
            for (ItemStack s : Sky.service(Features.Equipment.class).worn(p)) {
                if (BELT.equals(Sky.items().idOf(s))) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    /** The Hunting attribute Cocoon Chance (Primordial shard), in percent; 0 without the hunting module or dial. */
    static double attributeChance(Player p) {
        if (!SlayerDials.HUNTING_ATTRIBUTES.get()) return 0;
        try {
            return Math.max(0, Sky.service(Features.Hunting.class).attributeLevel(p, "value:cocoon_chance"));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    boolean isHatched(UUID mob) { return hatched.containsKey(mob); }

    /** Remove and return the hatch record of a monster that just died (null for anything else). */
    Hatch take(UUID mob) { return hatched.remove(mob); }

    /**
     * A slayer boss / miniboss of this player just died at {@code at}: roll the cocoon. {@code deadId} is the dead
     * entity (null when unknown); a monster that itself hatched never cocoons again.
     */
    void maybe(Player p, String mobId, Location at, SlayerCatalog.Type t, int tier, boolean boss, double maxHealth, UUID deadId) {
        if (p == null || mobId == null || at == null || at.getWorld() == null) return;
        boolean again = deadId != null && hatched.containsKey(deadId);
        double c = SlayerMath.cocoonChance(wearing(p), SlayerDials.PRIMORDIAL_CHANCE.get(), attributeChance(p), again);
        if (c <= 0 || ThreadLocalRandom.current().nextDouble() >= c) return;
        start(p, mobId, at, t, tier, boss, maxHealth);
    }

    /** Wrap it now (also the admin test path). */
    void start(Player p, String mobId, Location at, SlayerCatalog.Type t, int tier, boolean boss, double maxHealth) {
        cocoons++;
        Location spot = BossFight.ground(at.clone());
        MobDef def = Sky.mobs().def(mobId);
        String name = def == null ? "monster" : def.name;
        Msg.send(p, "&5&lCOCOONED! &7Your belt wrapped the &" + (t == null ? "c" : t.color) + Text.strip(name)
                + " &7in a cocoon. Slay it again when it hatches!");
        Fx.sound(spot, Sound.BLOCK_WOOL_PLACE, 1.2f, 0.6f);
        ItemDisplay shell = null;
        try {
            shell = spot.getWorld().spawn(spot.clone().add(0, 1, 0), ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(new ItemStack(Material.COBWEB));
                d.setBillboard(Display.Billboard.FIXED);
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1.6f, 2.2f, 1.6f), new AxisAngle4f()));
            });
            shells.add(shell);
        } catch (RuntimeException ignored) { }
        final ItemDisplay sh = shell;
        long hatchTicks = Math.max(10L, Math.round(SlayerDials.COCOON_HATCH_SECONDS.get() * 20));
        for (long i = 0; i < hatchTicks; i += 10) {
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                Fx.particle(spot.clone().add(0, 1, 0), Particle.ITEM_COBWEB, 8, 0.5, 0.02);
                Fx.sound(spot, Sound.BLOCK_SLIME_BLOCK_STEP, 0.6f, 0.7f);
            }, i);
        }
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (sh != null) { try { sh.remove(); } catch (RuntimeException ignored) { } shells.remove(sh); }
            hatch(p, mobId, spot, t, tier, boss, maxHealth);
        }, hatchTicks);
    }

    private void hatch(Player p, String mobId, Location spot, SlayerCatalog.Type t, int tier, boolean boss, double maxHealth) {
        if (!p.isOnline() || spot.getWorld() == null) return;
        LivingEntity m;
        try {
            m = Sky.mobs().spawn(mobId, spot, p);
        } catch (RuntimeException e) {
            Sky.log().warning("[slayer] cocoon could not hatch " + mobId + ": " + e.getMessage());
            return;
        }
        if (m == null) return;
        double hp = Math.max(1, maxHealth) * Math.max(0.01, SlayerDials.COCOON_HEALTH_PERCENT.get()) / 100.0;
        if (maxHealth > 0) Sky.mobs().setMaxHealth(m, hp);
        long life = Math.max(10, SlayerDials.COCOON_LIFETIME.get()) * 1000L;
        hatched.put(m.getUniqueId(), new Hatch(p.getUniqueId(), t == null ? null : t.id, tier, boss, System.currentTimeMillis() + life));
        if (m instanceof org.bukkit.entity.Mob mm) mm.setTarget(p);
        Fx.particle(spot.clone().add(0, 1, 0), Particle.EXPLOSION, 2, 0.4, 0);
        Fx.sound(spot, Sound.ENTITY_SPIDER_DEATH, 1f, 0.5f);
        Msg.send(p, "&5&lHATCHED! &7Slay it within &e" + (life / 1000) + "s &7for its loot again.");
    }

    /** Every second: hatched monsters past their lifetime crumble. */
    void tick() {
        if (hatched.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Hatch> e : new ArrayList<>(hatched.entrySet())) {
            Entity ent = Bukkit.getEntity(e.getKey());
            boolean gone = !(ent instanceof LivingEntity le) || !le.isValid() || le.isDead();
            if (!gone && now < e.getValue().expires()) continue;
            hatched.remove(e.getKey());
            if (!gone) {
                LivingEntity le = (LivingEntity) ent;
                Fx.particle(le.getLocation().add(0, 1, 0), Particle.ITEM_COBWEB, 12, 0.5, 0.02);
                if (Sky.mobs().isSkyMob(le)) Sky.mobs().remove(le); else le.remove();
                Player o = Bukkit.getPlayer(e.getValue().owner());
                if (o != null) Msg.send(o, "&7Your hatched monster crumbled back into silk.");
            }
        }
    }

    void shutdown() {
        for (Entity s : shells) { try { s.remove(); } catch (RuntimeException ignored) { } }
        shells.clear();
        for (UUID id : new ArrayList<>(hatched.keySet())) {
            Entity ent = Bukkit.getEntity(id);
            if (ent instanceof LivingEntity le && Sky.mobs().isSkyMob(le)) Sky.mobs().remove(le);
            else if (ent != null) ent.remove();
        }
        hatched.clear();
    }

    int live() { return hatched.size(); }
}
