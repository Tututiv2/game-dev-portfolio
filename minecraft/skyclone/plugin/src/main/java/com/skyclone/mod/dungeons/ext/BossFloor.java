package com.skyclone.mod.dungeons.ext;

import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.mob.MobDef;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * EXTENSION POINT (agent B): the runtime half of one boss floor. Register with {@link Bosses#register} under the
 * floor key ("F1".."F7"; a Master Mode floor uses its "M<n>" registration if there is one, else the "F<n>" one with
 * {@link BossContext#master()} true; with neither, the framework's default boss runs).
 *
 * <p>Lifecycle (all main thread): the arena blocks are built with the run ({@link #arena()}, pure). When the first
 * player steps through the blood room's portal, the framework calls {@link #start}; then {@link #tick} every 5 ticks
 * until the fight ends. Call {@link BossContext#complete()} when the boss is dead (the framework scores the run, pays
 * XP, then calls the reward hook) or {@link BossContext#fail(String)}. {@link #stop} always runs when the run closes.
 *
 * <p>One BossFloor INSTANCE is shared by every run of that floor: keep per-run state in {@link BossContext#state()}.
 */
public interface BossFloor {

    /** The arena builder (pure). */
    BossArena arena();

    void start(BossContext ctx);

    default void tick(BossContext ctx) {}

    /** A mob spawned through {@link BossContext#spawnMob} or {@link BossContext#spawnBoss} died. */
    default void onMobKill(BossContext ctx, LivingEntity mob, MobDef def, Player killer) {}

    /** Any hit on a mob of this fight, before it is applied (immunity phases, caps). */
    default void onDamage(BossContext ctx, DamageContext dc) {}

    /** A player of this run died during the fight (already a ghost). */
    default void onPlayerDeath(BossContext ctx, Player p) {}

    /** The run is closing (complete, failed or abandoned): remove anything you spawned outside the context's tracking. */
    default void stop(BossContext ctx) {}
}
