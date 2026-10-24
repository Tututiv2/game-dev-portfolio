package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sea creatures once they are out of the water: spawning at the bobber, the per-player and
 * per-island caps, the 6-minute despawn, Fishing XP on kill, the rod x2 and arrow-immunity rules,
 * and the scripted behaviours (Hydra split, slams, fireballs, lightning, gifts). Research 07 §3.1,
 * §3.4, §6.3.
 */
final class SeaCreatures implements Listener {

    static final NamespacedKey CORRUPT = new NamespacedKey("skyclone", "fishing_corrupt");
    static final NamespacedKey SPLIT = new NamespacedKey("skyclone", "fishing_split");
    static final NamespacedKey GIFTS = new NamespacedKey("skyclone", "fishing_gifts");

    record Tracked(UUID owner, String mob, long born, boolean corrupted, int chum, String world) {}

    static final Map<UUID, Tracked> LIVE = new HashMap<>();
    private static final Map<UUID, Long> NEXT_SLAM = new HashMap<>();
    private static final Map<UUID, Long> NEXT_FIRE = new HashMap<>();
    private static final Map<UUID, Long> NEXT_BOLT = new HashMap<>();
    private static final Map<UUID, Long> ARROW_MSG = new HashMap<>();

    // ---- spawning ------------------------------------------------------------------------------

    static int count(UUID owner) {
        int n = 0;
        for (Tracked t : LIVE.values()) if (t.owner.equals(owner)) n++;
        return n;
    }

    static int countWorld(World w) {
        int n = 0;
        for (Tracked t : LIVE.values()) if (t.world.equals(w.getName())) n++;
        return n;
    }

    /** True when this player may hook another creature here (research: 10 per player, 60 per lobby). */
    static boolean roomFor(Player p) {
        return count(p.getUniqueId()) < FishDials.CAP_PLAYER.get() && countWorld(p.getWorld()) < FishDials.CAP_WORLD.get();
    }

    /**
     * Spawn a creature at {@code at}, owned by {@code p}, flung toward them. {@code corruptChance}
     * (Corruption enchant), {@code frailPct} (Frail), {@code chum} (Glowy Chum Bait) come from the cast.
     */
    static LivingEntity spawn(Player p, String mobId, Location at, double corruptChance, double frailPct, int chum, boolean fling) {
        MobDef def = Sky.mobs().def(mobId);
        if (def == null) return null;
        LivingEntity le;
        try { le = Sky.mobs().spawn(mobId, at, p); } catch (RuntimeException e) { Sky.log().warning("[fishing] spawn " + mobId + ": " + e); return null; }
        if (le == null) return null;
        boolean corrupted = corruptChance > 0 && ThreadLocalRandom.current().nextDouble() < corruptChance;
        double max = def.health;
        if (corrupted) {
            max *= FishDials.CORRUPT_HP_MULT.get();
            le.getPersistentDataContainer().set(CORRUPT, PersistentDataType.BYTE, (byte) 1);
            le.setGlowing(true);
        }
        if (frailPct > 0) max *= Math.max(0.05, 1 - frailPct / 100.0);
        if (max != def.health) Sky.mobs().setMaxHealth(le, Math.max(1, max));
        LIVE.put(le.getUniqueId(), new Tracked(p.getUniqueId(), mobId, System.currentTimeMillis(), corrupted, chum, le.getWorld().getName()));
        if (fling) {
            Vector v = p.getLocation().toVector().subtract(at.toVector());
            if (v.lengthSquared() > 0.01) le.setVelocity(v.normalize().multiply(0.6).setY(0.45));
        }
        if (corrupted) Msg.send(p, "&5&lCORRUPTED! &dYour line dragged up a twisted creature.");
        return le;
    }

    // ---- housekeeping --------------------------------------------------------------------------

    static void start() {
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), SeaCreatures::sweep, 100L, 100L);
    }

    private static void sweep() {
        long maxAge = FishDials.DESPAWN_SECONDS.get() * 1000L;
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Tracked>> it = LIVE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Tracked> e = it.next();
            var ent = Bukkit.getEntity(e.getKey());
            Tracked t = e.getValue();
            if (!(ent instanceof LivingEntity le) || !le.isValid()) { it.remove(); forget(e.getKey()); continue; }
            Player owner = Bukkit.getPlayer(t.owner);
            boolean gone = owner == null || !owner.isOnline() || owner.getWorld() != le.getWorld();
            if (gone || now - t.born > maxAge) {
                Sky.mobs().remove(le);
                it.remove();
                forget(e.getKey());
                if (!gone && owner != null) Msg.send(owner, "&7Your " + Fishing.mobName(t.mob) + " &7slipped back into the depths.");
            }
        }
    }

    private static void forget(UUID id) {
        NEXT_SLAM.remove(id);
        NEXT_FIRE.remove(id);
        NEXT_BOLT.remove(id);
    }

    static int removeAll(UUID owner) {
        int n = 0;
        for (Iterator<Map.Entry<UUID, Tracked>> it = LIVE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Tracked> e = it.next();
            if (owner != null && !e.getValue().owner.equals(owner)) continue;
            var ent = Bukkit.getEntity(e.getKey());
            if (ent instanceof LivingEntity le && le.isValid()) Sky.mobs().remove(le);
            it.remove();
            n++;
        }
        return n;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removeAll(e.getPlayer().getUniqueId());
        ARROW_MSG.remove(e.getPlayer().getUniqueId());
    }

    // ---- kills ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(SkyEvents.MobKill e) {
        if (!Fishing.isSeaCreature(e.def)) return;
        Tracked t = LIVE.remove(e.mob.getUniqueId());
        forget(e.mob.getUniqueId());
        Player p = e.killer;
        if (p == null || !Fishing.ready(p)) return;
        boolean corrupted = t != null && t.corrupted
                || e.mob.getPersistentDataContainer().has(CORRUPT, PersistentDataType.BYTE);
        double mult = corrupted ? FishDials.CORRUPT_REWARD_MULT.get() : 1.0;
        e.coins *= mult;
        Fishing.addXp(p, Fishing.fishingXpOf(e.def) * mult, "sea_creature:" + e.def.id);
        FishData d = Fishing.data(p);
        d.kills.merge(e.def.id, 1L, Long::sum);
        // Powder (Ore Grubbers) goes through the mining service; a no-op when mining is absent.
        if (e.def.extra != null && e.def.extra.has("powder")) {
            var o = e.def.extra.getAsJsonObject("powder");
            for (String type : o.keySet())
                Sky.service(Features.Mining.class).addPowder(p, type, o.get(type).getAsLong(), "fishing: " + e.def.id);
        }
        // Chum: +1 per placed Chum Bucket in reach of the creature (up to +4), Glowy Chum Bait's +2 on top (ChumBuckets)
        int chum = ChumBuckets.chumFor(e.mob.getLocation(), t == null ? 0 : t.chum);
        if (chum > 0 && Sky.items().exists("CHUM")) Sky.loot().give(p, "CHUM", chum, LootSource.FISHING);
        if (corrupted) Trophies.awardCorrupted(p);
    }

    // ---- combat rules --------------------------------------------------------------------------

    static void registerCombat() {
        Sky.combat().addModifier("fishing_rod_vs_sea_creatures", 200, ctx -> {
            Player p = ctx.playerAttacker();
            if (p == null || ctx.kind != DamageContext.Kind.MELEE) return;
            if (!(ctx.victim instanceof LivingEntity le) || !Fishing.isSeaCreature(Sky.mobs().defOf(le))) return;
            ItemStack w = ctx.weapon;
            String id = w == null ? null : Sky.items().idOf(w);
            ItemDef wd = id == null ? null : Sky.items().def(id);
            if (wd != null && wd.category == ItemCategory.FISHING_ROD) ctx.multiplicative *= FishDials.ROD_DAMAGE_MULT.get();
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        if (!FishDials.ARROW_IMMUNITY.get() || ctx.kind != DamageContext.Kind.PROJECTILE) return;
        Player p = ctx.playerAttacker();
        if (p == null || !(ctx.victim instanceof LivingEntity le) || !Fishing.isSeaCreature(Sky.mobs().defOf(le))) return;
        String w = ctx.weapon == null ? null : Sky.items().idOf(ctx.weapon);
        if ("PRISMARINE_BOW".equals(w)) return;
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        if (now - ARROW_MSG.getOrDefault(p.getUniqueId(), 0L) > 5000) {
            ARROW_MSG.put(p.getUniqueId(), now);
            Msg.send(p, "&cSea creatures shrug off arrows. Use your rod or a blade &7(a Prismarine Bow works too).");
        }
    }

    // ---- behaviours ----------------------------------------------------------------------------

    static void registerBehaviours() {
        Sky.mobs().registerBehaviour("fishing_split", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (!FishDials.HYDRA_SPLIT.get()) return;
                if (mob.getPersistentDataContainer().has(SPLIT, PersistentDataType.BYTE)) return;
                double max = Sky.mobs().maxHealth(mob), hp = Sky.mobs().health(mob);
                double at = def.extra != null && def.extra.has("splitAt") ? def.extra.get("splitAt").getAsDouble() : 0.5;
                if (max <= 0 || hp > max * at || hp <= 0) return;
                mob.getPersistentDataContainer().set(SPLIT, PersistentDataType.BYTE, (byte) 1);
                Player owner = owner(mob);
                if (owner == null) return;
                LivingEntity twin = spawn(owner, def.id, mob.getLocation().add(1, 0.5, 0), 0, 0, 0, false);
                if (twin == null) return;
                twin.getPersistentDataContainer().set(SPLIT, PersistentDataType.BYTE, (byte) 1);
                Sky.mobs().setHealth(twin, Math.max(1, Sky.mobs().maxHealth(twin) * at));
                mob.getWorld().spawnParticle(Particle.SPLASH, mob.getLocation().add(0, 1, 0), 60, 0.8, 0.8, 0.8, 0.2);
                mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 1.2f);
                Msg.send(owner, "&3The " + def.name + " &3splits in two!");
            }
        });
        Sky.mobs().registerBehaviour("fishing_slam", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                long now = System.currentTimeMillis();
                double every = num(def, "slamEvery", 8);
                Long next = NEXT_SLAM.get(mob.getUniqueId());
                if (next == null) { NEXT_SLAM.put(mob.getUniqueId(), now + (long) (every * 1000)); return; }
                if (now < next) return;
                NEXT_SLAM.put(mob.getUniqueId(), now + (long) (every * 1000));
                Player owner = owner(mob);
                double r = num(def, "slamRadius", 4);
                if (owner == null || owner.getWorld() != mob.getWorld() || owner.getLocation().distanceSquared(mob.getLocation()) > (r + 1) * (r + 1)) return;
                Location c = mob.getLocation();
                c.getWorld().spawnParticle(Particle.EXPLOSION, c.clone().add(0, 0.3, 0), 3, r / 3, 0.1, r / 3, 0);
                c.getWorld().playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.7f);
                Sky.combat().damagePlayer(owner, def.damage * num(def, "slamMult", 1.5), mob, DamageContext.Kind.MOB);
                owner.setVelocity(owner.getVelocity().add(new Vector(0, 0.6, 0)));
            }
        });
        Sky.mobs().registerBehaviour("fishing_fireball", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                long now = System.currentTimeMillis();
                double every = num(def, "fireballEvery", 4);
                Long next = NEXT_FIRE.get(mob.getUniqueId());
                if (next == null) { NEXT_FIRE.put(mob.getUniqueId(), now + (long) (every * 1000)); return; }
                if (now < next) return;
                NEXT_FIRE.put(mob.getUniqueId(), now + (long) (every * 1000));
                Player owner = owner(mob);
                if (owner == null || owner.getWorld() != mob.getWorld() || owner.getLocation().distanceSquared(mob.getLocation()) > 24 * 24) return;
                if (!mob.hasLineOfSight(owner)) return;
                Vector dir = owner.getEyeLocation().toVector().subtract(mob.getEyeLocation().toVector()).normalize();
                try {
                    SmallFireball fb = mob.launchProjectile(SmallFireball.class, dir.multiply(0.9));
                    fb.setIsIncendiary(false);
                    fb.setYield(0);
                } catch (RuntimeException ignored) { }
            }
        });
        Sky.mobs().registerBehaviour("fishing_lightning", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                long now = System.currentTimeMillis();
                double every = num(def, "lightningEvery", 5);
                Long next = NEXT_BOLT.get(mob.getUniqueId());
                if (next == null) { NEXT_BOLT.put(mob.getUniqueId(), now + (long) (every * 1000)); return; }
                if (now < next) return;
                NEXT_BOLT.put(mob.getUniqueId(), now + (long) (every * 1000));
                Player owner = owner(mob);
                if (owner == null || owner.getWorld() != mob.getWorld() || owner.getLocation().distanceSquared(mob.getLocation()) > 20 * 20) return;
                owner.getWorld().strikeLightningEffect(owner.getLocation());
                Sky.combat().damagePlayer(owner, def.damage * 0.75, mob, DamageContext.Kind.MOB);
            }
        });
        Sky.mobs().registerBehaviour("fishing_gifts", new MobBehaviour() {
            @Override public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
                Player p = ctx.playerAttacker();
                if (p == null || ThreadLocalRandom.current().nextDouble() > FishDials.GIFT_CHANCE.get()) return;
                // capped per creature: without a cap, a weak weapon (many hits) would farm gifts for ever
                var pdc = mob.getPersistentDataContainer();
                int given = pdc.getOrDefault(GIFTS, PersistentDataType.INTEGER, 0);
                if (given >= FishDials.GIFT_MAX.get()) return;
                pdc.set(GIFTS, PersistentDataType.INTEGER, given + 1);
                String gift = ThreadLocalRandom.current().nextDouble() < 0.7 ? "WHITE_GIFT" : "GREEN_GIFT";
                if (Sky.items().exists(gift)) Sky.loot().give(p, gift, 1, LootSource.FISHING);
            }
        });
    }

    static Player owner(LivingEntity mob) {
        UUID o = Sky.mobs().owner(mob);
        if (o == null) {
            Tracked t = LIVE.get(mob.getUniqueId());
            o = t == null ? null : t.owner;
        }
        return o == null ? null : Bukkit.getPlayer(o);
    }

    private static double num(MobDef d, String key, double def) {
        return d.extra != null && d.extra.has(key) ? d.extra.get(key).getAsDouble() : def;
    }

    /** "&3Water Hydra &7(2/10 alive)" style status for the action bar. */
    static String actionBar(Player p) {
        int n = count(p.getUniqueId());
        if (n == 0) return null;
        return "&3⚓ Sea Creatures: &b" + n + "&7/" + FishDials.CAP_PLAYER.get();
    }

    static String describe(Tracked t) {
        long age = (System.currentTimeMillis() - t.born) / 1000;
        return Fishing.mobName(t.mob) + " &7" + Text.duration(age) + (t.corrupted ? " &5(corrupted)" : "");
    }
}
