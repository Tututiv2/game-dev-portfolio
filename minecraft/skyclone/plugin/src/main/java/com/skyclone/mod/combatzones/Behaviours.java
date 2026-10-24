package com.skyclone.mod.combatzones;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Scripted mob abilities for the Spider's Den and The End (ids match MobDef.behaviours). */
final class Behaviours implements Listener {

    private final CombatzonesModule m;
    private final Random rng = new Random();
    private final NamespacedKey variantKey;
    private final NamespacedKey riderKey;
    private final java.util.Map<java.util.UUID, java.util.UUID> riders = new java.util.HashMap<>();
    final List<DropDef> zealotChest = new ArrayList<>();

    Behaviours(CombatzonesModule m) {
        this.m = m;
        variantKey = new NamespacedKey(Sky.plugin(), "cz_zealot");
        riderKey = new NamespacedKey(Sky.plugin(), "cz_jockey_rider");
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::sweepRiders, 100L, 100L);
        loadZealotVariants();
        var mobs = Sky.mobs();

        // Splitter Spider: bursts into two silverfish when it dies (research 03 sec 31.2)
        mobs.registerBehaviour("den_splitter", new MobBehaviour() {
            @Override public void onDeath(LivingEntity mob, MobDef def, Player killer) {
                String fish = def.level >= 40 ? "den_silverfish_42" : "den_silverfish_2";
                Location l = mob.getLocation();
                Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                    for (int i = 0; i < 2; i++) Sky.mobs().spawn(fish, l.clone().add(i == 0 ? 0.6 : -0.6, 0.2, 0));
                });
            }
        });
        // Dasher Spider: pounces at its target
        mobs.registerBehaviour("den_dasher", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (tick % 16 != 0 || !(mob instanceof Mob mm) || !(mm.getTarget() instanceof Player t)) return;
                double d = t.getLocation().distanceSquared(mob.getLocation());
                if (d < 9 || d > 144 || !mob.isOnGround()) return;
                Vector v = t.getLocation().toVector().subtract(mob.getLocation().toVector()).normalize().multiply(1.1).setY(0.45);
                mob.setVelocity(v);
                mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 1f, 1.6f);
            }
        });
        // Weaver Spider: its bite tangles you in silk
        mobs.registerBehaviour("den_weaver", new MobBehaviour() {
            @Override public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
                if (rng.nextInt(3) == 0) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2, false, true));
                    target.getWorld().spawnParticle(Particle.ITEM_COBWEB, target.getLocation().add(0, 1, 0), 12, 0.4, 0.5, 0.4, 0);
                }
            }
        });
        // Voracious Spider: heals by feeding
        mobs.registerBehaviour("den_voracious", new MobBehaviour() {
            @Override public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
                double max = Sky.mobs().maxHealth(mob);
                Sky.mobs().setHealth(mob, Math.min(max, Sky.mobs().health(mob) + max * 0.05));
            }
        });
        // Spider Jockey: the rider shoots arrows at range
        mobs.registerBehaviour("den_jockey", new MobBehaviour() {
            // wave 5: a visible skeleton rider - a vanilla prop (no AI, invulnerable, never saved), not a sky mob;
            // the shots stay the spider's own ranged hit. Riders whose spider vanished are swept every 5 s.
            @Override public void onSpawn(LivingEntity mob, MobDef def) {
                try {
                    org.bukkit.entity.Skeleton rider = mob.getWorld().spawn(mob.getLocation(), org.bukkit.entity.Skeleton.class, s -> {
                        s.setAI(false);
                        s.setInvulnerable(true);
                        s.setSilent(true);
                        s.setPersistent(false);
                        s.setRemoveWhenFarAway(false);
                        s.setCollidable(false);
                        try { s.setShouldBurnInDay(false); } catch (Throwable ignored) { }
                        s.getPersistentDataContainer().set(riderKey, PersistentDataType.BYTE, (byte) 1);
                        if (s.getEquipment() != null) s.getEquipment().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.BOW));
                    });
                    mob.addPassenger(rider);
                    riders.put(rider.getUniqueId(), mob.getUniqueId());
                } catch (RuntimeException ex) { /* a refused spawn just means no rider */ }
            }
            @Override public void onDeath(LivingEntity mob, MobDef def, Player killer) {
                for (org.bukkit.entity.Entity e : mob.getPassengers()) if (e.getPersistentDataContainer().has(riderKey)) { riders.remove(e.getUniqueId()); e.remove(); }
            }
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (tick % 12 != 0 || !(mob instanceof Mob mm) || !(mm.getTarget() instanceof Player t)) return;
                double d = t.getLocation().distanceSquared(mob.getLocation());
                if (d < 16 || d > 196 || !mob.hasLineOfSight(t)) return;
                mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_SKELETON_SHOOT, 1f, 1f);
                Sky.combat().damagePlayer(t, def.damage * 0.5, mob, DamageContext.Kind.PROJECTILE);
            }
        });
        // Toxic Rain Slime: poison
        mobs.registerBehaviour("den_toxic", new MobBehaviour() {
            @Override public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0, false, true));
            }
        });
        mobs.registerBehaviour("den_keeper", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (tick % 20 == 0) mob.getWorld().spawnParticle(Particle.SMOKE, mob.getLocation().add(0, 0.5, 0), 4, 0.4, 0.2, 0.4, 0);
            }
        });
        // Broodmother: calls cave spiders
        mobs.registerBehaviour("den_broodmother", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (tick % 60 != 0 || !(mob instanceof Mob mm) || mm.getTarget() == null) return;
                if (Sky.mobs().live("den_cave_spider").size() > 24) return;
                for (int i = 0; i < 2; i++) Sky.mobs().spawn("den_cave_spider", mob.getLocation().clone().add(rng.nextInt(5) - 2, 0.3, rng.nextInt(5) - 2));
                mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_SPIDER_HURT, 1.5f, 0.5f);
            }
        });
        // Silkmother: webs everyone close to her, leaps at the farthest attacker
        mobs.registerBehaviour("den_arachne", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (tick % 40 == 0) {
                    for (Player p : mob.getWorld().getPlayers()) {
                        if (p.getLocation().distanceSquared(mob.getLocation()) > 64) continue;
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 3, false, true));
                        p.getWorld().spawnParticle(Particle.ITEM_COBWEB, p.getLocation().add(0, 1, 0), 20, 0.5, 0.6, 0.5, 0);
                    }
                }
                if (tick % 28 == 0 && mob instanceof Mob mm && mm.getTarget() instanceof Player t && mob.isOnGround()
                        && t.getLocation().distanceSquared(mob.getLocation()) > 36) {
                    mob.setVelocity(t.getLocation().toVector().subtract(mob.getLocation().toVector()).normalize().multiply(1.3).setY(0.6));
                }
            }
        });
        // Zealot: rolled once at spawn into a plain, an ender-chest or a Special Zealot
        mobs.registerBehaviour("end_zealot", new MobBehaviour() {
            @Override public void onSpawn(LivingEntity mob, MobDef def) {
                if (!def.id.startsWith("end_zealot_5")) return;               // bruisers stay plain
                double r = rng.nextDouble();
                // wave 5: the nearest player's Zealot's Fortune perk + Enderman pet "Zealot Madness" raise the special odds
                double special = m.dials.specialZealotChance.get() * (m.essence == null ? 1 : m.essence.specialZealotMultiplier(nearest(mob, 48)));
                String v = r < special ? "special" : r < special + m.dials.zealotChestChance.get() ? "chest" : null;
                if (v == null) return;
                mob.getPersistentDataContainer().set(variantKey, PersistentDataType.STRING, v);
                if (mob instanceof Enderman en && v.equals("chest")) en.setCarriedBlock(Material.ENDER_CHEST.createBlockData());
                if (v.equals("special")) {
                    mob.setGlowing(true);
                    Sky.mobs().setDisplayName(mob, "&dSpecial Zealot");        // core wave 5 (our request 7)
                }
            }
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (tick % 10 == 0 && "special".equals(mob.getPersistentDataContainer().get(variantKey, PersistentDataType.STRING)))
                    mob.getWorld().spawnParticle(Particle.WITCH, mob.getLocation().add(0, 1.5, 0), 6, 0.3, 0.6, 0.3, 0);
            }
        });
        // Seer: calls endermen to its side
        mobs.registerBehaviour("end_seer", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (tick % 40 != 0 || !(mob instanceof Mob mm) || !(mm.getTarget() instanceof Player)) return;
                int near = 0;
                for (LivingEntity le : Sky.mobs().live("end_enderman_42")) if (le.getWorld() == mob.getWorld() && le.getLocation().distanceSquared(mob.getLocation()) < 100) near++;
                if (near >= 2) return;
                Sky.mobs().spawn("end_enderman_42", mob.getLocation().clone().add(rng.nextInt(5) - 2, 0.3, rng.nextInt(5) - 2));
                mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
            }
        });
        mobs.registerBehaviour("end_defender", new MobBehaviour() {
            @Override public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
                if (ctx.kind == DamageContext.Kind.PROJECTILE) ctx.multiplicative *= 0.5;        // TUNE: obsidian shrugs off arrows
            }
        });
        // End Stone Colossus: ground slam
        mobs.registerBehaviour("end_protector", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (tick % 32 != 0) return;
                boolean hit = false;
                for (Player p : mob.getWorld().getPlayers()) {
                    if (p.getLocation().distanceSquared(mob.getLocation()) > 36) continue;
                    Sky.combat().damagePlayer(p, def.damage, mob, DamageContext.Kind.MOB);
                    p.setVelocity(p.getLocation().toVector().subtract(mob.getLocation().toVector()).normalize().multiply(1.2).setY(0.8));
                    hit = true;
                }
                if (hit) {
                    mob.getWorld().spawnParticle(Particle.EXPLOSION, mob.getLocation(), 4, 2, 0.2, 2, 0);
                    mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1.5f, 0.5f);
                }
            }
        });
        mobs.registerBehaviour("end_dragon", new MobBehaviour() {});
    }

    private void loadZealotVariants() {
        try (var in = Sky.plugin().getResource("data/combatzones/zealot_variants.json")) {
            if (in == null) return;
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray chest = o.getAsJsonArray("chest");
            for (JsonElement e : chest) {
                JsonObject r = e.getAsJsonObject();
                DropDef d = new DropDef(r.get("item").getAsString(), 1, 1, r.get("chance").getAsDouble());
                d.magicFind = false;
                d.label = DropDef.Label.NONE;
                zealotChest.add(d);
            }
        } catch (Exception ex) {
            Sky.log().warning("[combatzones] zealot variants: " + ex);
        }
    }

    /** The ender chest holds exactly one prize (weighted), the Special Zealot a Summoning Eye. */
    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || e.def == null || !e.def.id.startsWith("end_zealot_5")) return;
        String v = e.mob.getPersistentDataContainer().get(variantKey, PersistentDataType.STRING);
        if (v == null || !Sky.profiles().isReady(e.killer)) return;
        if (v.equals("special")) {
            Sky.loot().give(e.killer, "SUMMONING_EYE", 1, LootSource.MOB_DROP);
            Msg.rareDrop(e.killer, "&d&lCRAZY RARE DROP!", "&5Summoning Eye", 0);
            Msg.send(e.killer, "&5☬ &dYou slew a Special Zealot!");
        } else if (v.equals("chest") && !zealotChest.isEmpty()) {
            double total = 0;
            for (DropDef d : zealotChest) total += d.chance;
            double x = rng.nextDouble() * total;
            for (DropDef d : zealotChest) {
                x -= d.chance;
                if (x <= 0) {
                    if (Sky.items().exists(d.item)) {
                        Sky.loot().give(e.killer, d.item, 1, LootSource.MOB_DROP);
                        Msg.send(e.killer, "&5☬ &7The Zealot's ender chest held " + Sky.items().def(d.item).rarity.code() + com.skyclone.api.ui.Text.strip(Sky.items().def(d.item).name) + "&7!");
                    }
                    break;
                }
            }
        }
    }

    double magicFind(Player p, MobDef def) { return Sky.loot().magicFind(p, def); }

    /** Removes jockey riders whose spider is gone (killed silently, unloaded, leashed away). */
    private void sweepRiders() {
        riders.entrySet().removeIf(en -> {
            org.bukkit.entity.Entity rider = Bukkit.getEntity(en.getKey());
            if (rider == null) return true;
            org.bukkit.entity.Entity spider = Bukkit.getEntity(en.getValue());
            if (spider == null || !spider.isValid() || rider.getVehicle() == null) { rider.remove(); return true; }
            return false;
        });
    }

    void removeRiders() {
        for (java.util.UUID u : riders.keySet()) { org.bukkit.entity.Entity e = Bukkit.getEntity(u); if (e != null) e.remove(); }
        riders.clear();
    }

    static Player nearest(LivingEntity mob, double r) {
        Player best = null;
        double bd = r * r;
        for (Player p : mob.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(mob.getLocation());
            if (d <= bd) { bd = d; best = p; }
        }
        return best;
    }
}
