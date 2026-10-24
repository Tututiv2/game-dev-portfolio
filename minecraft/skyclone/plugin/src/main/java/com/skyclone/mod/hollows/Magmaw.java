package com.skyclone.mod.hollows;

import com.google.gson.Gson;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Magmaw (reference: Bal), the boss of Emberdeep. It has 200 health and every player hit takes exactly
 * one point off it, whatever the weapon (research 03 / NEU BAL_BOSS). Every hit also rolls the per-hit
 * table (Flawed Ruby / Flawed Topaz 5%, Cinder Morsel 4.5%, the Magmaw pet). It calls blazes out of the
 * lava while the fight goes on. When it dies everyone who hit it in the last minute gets a Topaz crystal.
 */
final class Magmaw implements MobBehaviour {

    static final class Table { List<DropDef> perHit = new ArrayList<>(); String crystal = "TOPAZ_CRYSTAL"; double health = 200; }

    private final HollowsModule m;
    final Table table;
    /** mob uuid -> player uuid -> last hit time. */
    private final Map<UUID, Map<UUID, Long>> hitters = new HashMap<>();
    private final Map<UUID, Long> lastSummon = new HashMap<>();

    Magmaw(HollowsModule m) {
        this.m = m;
        Table t = null;
        try (var in = Sky.plugin().getResource("data/hollows/magmaw.json")) {
            if (in != null) t = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Table.class);
        } catch (Exception e) {
            Sky.log().warning("[hollows] magmaw.json unreadable: " + e);
        }
        table = t != null ? t : new Table();
    }

    @Override
    public void onSpawn(LivingEntity mob, MobDef def) {
        hitters.put(mob.getUniqueId(), new HashMap<>());
        Location at = mob.getLocation();
        for (Player p : at.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(at) < 80 * 80) {
                Msg.send(p, "&c&lMAGMAW &7rises out of the Emberdeep forge! &8(" + (int) table.health + " hits)");
                Msg.sound(p, Sound.ENTITY_BLAZE_SHOOT, 0.5f);
            }
    }

    @Override
    public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null) { ctx.finalDamage = 0; return; }
        ctx.finalDamage = Math.max(0, m.dials.magmawHitDamage.get());
        hitters.computeIfAbsent(mob.getUniqueId(), k -> new HashMap<>()).put(p.getUniqueId(), System.currentTimeMillis());
        if (!table.perHit.isEmpty() && Sky.profiles().isReady(p))
            Sky.loot().roll(p, table.perHit, LootSource.MOB_DROP, Sky.stats().get(p, Stat.MAGIC_FIND));
    }

    @Override
    public void onTick(LivingEntity mob, MobDef def, long tick) {
        Location at = mob.getLocation();
        at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(0, 1, 0), 6, 1.2, 0.8, 1.2, 0.01);
        long now = System.currentTimeMillis();
        long last = lastSummon.getOrDefault(mob.getUniqueId(), now);
        lastSummon.putIfAbsent(mob.getUniqueId(), now);
        if (now - last < m.dials.magmawSummonSeconds.get() * 1000L) return;
        lastSummon.put(mob.getUniqueId(), now);
        Map<UUID, Long> h = hitters.get(mob.getUniqueId());
        if (h == null || h.isEmpty()) return;                         // nobody is fighting it
        // NEU BAL_BOSS: "At various points in the fight Bal will summon Fire Bats and Blazes" - the waves alternate
        int wave = waves.merge(mob.getUniqueId(), 1, Integer::sum);
        String kind = wave % 2 == 1 ? BLAZE : BAT;
        int alive = Sky.mobs().live(kind).size();
        int n = kind.equals(BAT) ? 3 : 2;
        for (int i = 0; i < n && alive < m.dials.magmawSummonCap.get(); i++, alive++) {
            Location s = at.clone().add((Math.random() - 0.5) * 10, kind.equals(BAT) ? 2.5 : 1, (Math.random() - 0.5) * 10);
            if (!s.getBlock().isPassable()) s = at.clone().add(0, 1, 0);
            try { Sky.mobs().spawn(kind, s); } catch (Exception ignored) { }
        }
    }

    static final String BLAZE = "ch_magmaw_blaze", BAT = "ch_magmaw_bat";
    private final Map<UUID, Integer> waves = new HashMap<>();

    /** Ember Bats: a burning touch (they are bats, so they have no attack of their own). */
    static final class EmberBat implements MobBehaviour {
        @Override
        public void onTick(LivingEntity mob, MobDef def, long tick) {
            if (tick % 10 != 0) return;
            mob.getWorld().spawnParticle(Particle.FLAME, mob.getLocation().add(0, 0.4, 0), 2, 0.15, 0.15, 0.15, 0.005);
            for (Player p : mob.getWorld().getPlayers()) {
                if (p.getLocation().add(0, 1, 0).distanceSquared(mob.getLocation()) > 2.2 * 2.2 || !Sky.profiles().isReady(p)) continue;
                Sky.combat().damagePlayer(p, Math.max(0, def.damage), mob, DamageContext.Kind.MOB);
                p.setFireTicks(Math.max(p.getFireTicks(), 40));
            }
        }
    }

    @Override
    public void onDeath(LivingEntity mob, MobDef def, Player killer) {
        Map<UUID, Long> h = hitters.remove(mob.getUniqueId());
        lastSummon.remove(mob.getUniqueId());
        waves.remove(mob.getUniqueId());
        Set<UUID> winners = new HashSet<>();
        long cutoff = System.currentTimeMillis() - 60_000;
        if (h != null) h.forEach((u, t) -> { if (t >= cutoff) winners.add(u); });
        if (killer != null) winners.add(killer.getUniqueId());
        for (UUID u : winners) {
            Player p = Sky.plugin().getServer().getPlayer(u);
            if (p == null || !Sky.profiles().isReady(p) || p.getWorld() != mob.getWorld()) continue;
            m.quests.reward(p, "TOPAZ", "&cMagmaw &7sinks back into the lava.");
        }
        for (String kind : List.of(BLAZE, BAT))
            for (LivingEntity b : new ArrayList<>(Sky.mobs().live(kind))) Sky.mobs().remove(b);
    }

    int hitsToKill() { return Lore.hitsToKill(table.health, m.dials.magmawHitDamage.get()); }
}
