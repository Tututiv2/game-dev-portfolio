package com.skyclone.mod.hollows;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Goblin Queen, boss of the Goblin Queen's Den (research 03: "Goblin Queen | Queen's Den | Amber
 * Crystal"). An ordinary-damage fight: at 75%, 50% and 25% health she screeches and calls Brood
 * Guards out of the clutch. When she falls, everyone who hit her in the last two minutes gets the
 * Amber crystal and one Goblin Egg (so the King's route is open to them too); her own drop table
 * (coloured eggs, goblin armour) rolls for the killer through core.
 */
final class GoblinQueen implements MobBehaviour {

    static final String MOB = "ch_goblin_queen", GUARD = "ch_goblin_brood_guard";
    static final double[] STAGES = {0.75, 0.50, 0.25};

    private final HollowsModule m;
    /** mob uuid -> player uuid -> last hit time. */
    private final Map<UUID, Map<UUID, Long>> hitters = new HashMap<>();
    /** mob uuid -> how many summon stages have fired. */
    private final Map<UUID, Integer> stage = new HashMap<>();

    GoblinQueen(HollowsModule m) { this.m = m; }

    @Override
    public void onSpawn(LivingEntity mob, MobDef def) {
        hitters.put(mob.getUniqueId(), new HashMap<>());
        stage.put(mob.getUniqueId(), 0);
        Location at = mob.getLocation();
        for (Player p : at.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(at) < 60 * 60) {
                Msg.send(p, "&6&lGOBLIN QUEEN &7has returned to her clutch in the &6Queen's Den&7.");
                Msg.sound(p, Sound.ENTITY_WITCH_CELEBRATE, 0.8f);
            }
    }

    @Override
    public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null) return;
        hitters.computeIfAbsent(mob.getUniqueId(), k -> new HashMap<>()).put(p.getUniqueId(), System.currentTimeMillis());
        double max = Sky.mobs().maxHealth(mob);
        if (max <= 0) return;
        double after = (Sky.mobs().health(mob) - Math.max(0, ctx.finalDamage)) / max;
        int done = stage.getOrDefault(mob.getUniqueId(), 0);
        if (done < STAGES.length && after <= STAGES[done] && after > 0) {
            stage.put(mob.getUniqueId(), done + 1);
            summonGuards(mob, (int) Math.round(STAGES[done] * 100));
        }
    }

    private void summonGuards(LivingEntity mob, int pct) {
        Location at = mob.getLocation();
        at.getWorld().playSound(at, Sound.ENTITY_WITCH_CELEBRATE, 1.5f, 0.6f);
        at.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, at.clone().add(0, 2, 0), 12, 1, 0.5, 1, 0);
        for (Player p : at.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(at) < 40 * 40)
                Msg.send(p, "&6&lGOBLIN QUEEN &7(" + pct + "%) &fscreeches! &7Her brood guards rush out of the clutch.");
        int alive = Sky.mobs().live(GUARD).size();
        int n = Math.max(0, m.dials.queenGuards.get());
        for (int i = 0; i < n && alive < n * 3; i++, alive++) {
            double a = Math.PI * 2 * i / Math.max(1, n);
            Location s = at.clone().add(Math.cos(a) * 3, 0.2, Math.sin(a) * 3);
            if (!s.getBlock().isPassable() || !s.clone().add(0, 1, 0).getBlock().isPassable()) s = at.clone().add(0, 0.2, 0);
            try { Sky.mobs().spawn(GUARD, s); } catch (Exception ignored) { }
        }
    }

    @Override
    public void onTick(LivingEntity mob, MobDef def, long tick) {
        if (tick % 20 == 0) mob.getWorld().spawnParticle(Particle.WAX_ON, mob.getLocation().add(0, 2.3, 0), 3, 0.3, 0.2, 0.3, 0);
    }

    @Override
    public void onDeath(LivingEntity mob, MobDef def, Player killer) {
        Map<UUID, Long> h = hitters.remove(mob.getUniqueId());
        stage.remove(mob.getUniqueId());
        Set<UUID> winners = new HashSet<>();
        long cutoff = System.currentTimeMillis() - 120_000;
        if (h != null) h.forEach((u, t) -> { if (t >= cutoff) winners.add(u); });
        if (killer != null) winners.add(killer.getUniqueId());
        for (UUID u : winners) {
            Player p = Sky.plugin().getServer().getPlayer(u);
            if (p == null || !Sky.profiles().isReady(p) || p.getWorld() != mob.getWorld()) continue;
            if (Sky.items().exists("GOBLIN_EGG")) Sky.loot().give(p, "GOBLIN_EGG", 1, LootSource.MOB_DROP);
            if (m.dials.queenGivesAmber.get()) m.quests.reward(p, "AMBER", "&6The Goblin Queen &7falls. Something amber glints in her clutch.");
            else Msg.send(p, "&6The Goblin Queen &7falls. Take her egg to &6King Grubnak&7.");
        }
    }

    /** For self-tests: number of guard waves in a full fight. */
    static int waves() { return STAGES.length; }
}
