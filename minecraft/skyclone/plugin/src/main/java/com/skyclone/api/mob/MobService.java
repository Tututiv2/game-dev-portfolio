package com.skyclone.api.mob;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

/**
 * Sky mobs: custom health pools far beyond vanilla, a live nameplate, scripted behaviours, and
 * loot/XP/coins on death. Vanilla natural spawning is OFF in every game world; everything alive
 * was spawned through here.
 *
 * <p>On death core fires {@code SkyEvents.MobKill}, then rolls drops through LootService (which
 * fires ItemGain for collections), pays coins, and lets skills grant combat XP from {@code def.combatXp}.
 */
public interface MobService {

    void register(MobDef def);

    int loadJson(String module, String file);

    MobDef def(String id);

    Collection<MobDef> all();

    void registerBehaviour(String id, MobBehaviour behaviour);

    void addSpawnRule(SpawnRule rule);

    int loadSpawnRules(String module, String file);

    /** Spawn a sky mob. {@code owner} (nullable) restricts damage/loot to one player — slayer bosses. */
    LivingEntity spawn(String mobId, Location at, Player owner);

    default LivingEntity spawn(String mobId, Location at) { return spawn(mobId, at, null); }

    /** The def of a live entity, or null if it is not a sky mob. */
    MobDef defOf(LivingEntity entity);

    boolean isSkyMob(LivingEntity entity);

    double health(LivingEntity mob);

    double maxHealth(LivingEntity mob);

    void setHealth(LivingEntity mob, double health);

    /** Override max health for this one instance (slayer tiers, dungeon scaling). */
    void setMaxHealth(LivingEntity mob, double max);

    /**
     * Let more players than the owner fight an owned mob (party members, co-op): they may damage it
     * and it may target them. Loot and kill credit still go to the OWNER. {@code null} = owner only.
     * Not persisted; set it right after spawn(). (Added by the core gate, 2026-10-02.)
     */
    void setAllies(LivingEntity mob, java.util.function.Predicate<Player> allies);

    /**
     * Show a different NAME on this one mob's nameplate (level, health and colours stay core's): a Special
     * Zealot, a renamed elite, a dungeon mini-boss sharing a def. Legacy colour codes allowed; {@code null}
     * restores the def's name. Stored on the entity, so it survives chunk reloads. (Wave 5, combatzones request 7.)
     */
    default void setDisplayName(LivingEntity mob, String name) {}

    /** Owner uuid of an owned mob (slayer boss), or null. */
    UUID owner(LivingEntity mob);

    /** Kill silently with no loot (despawn a boss on quest fail). */
    void remove(LivingEntity mob);

    /** All live sky mobs of one id. */
    Collection<LivingEntity> live(String mobId);
}
