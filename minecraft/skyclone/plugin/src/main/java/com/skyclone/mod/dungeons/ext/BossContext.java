package com.skyclone.mod.dungeons.ext;

import com.skyclone.api.world.gen.Box;
import com.skyclone.mod.dungeons.model.FloorDef;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** What a {@link BossFloor} can see and do in one run. Main thread only. */
public interface BossContext {

    String runId();

    FloorDef floor();

    boolean master();

    World world();

    /** A named arena point (recorded by {@link BossArena#build}) as a world Location (block centre), or null. */
    Location point(String name);

    /** Every arena point, world coordinates. */
    Map<String, Location> points();

    /** The arena footprint in WORLD coordinates. */
    Box arenaBox();

    /** Online members currently in the run world (ghosts included). */
    List<Player> players();

    /** Online members who are alive (not ghosts). */
    List<Player> alive();

    boolean isGhost(Player p);

    /**
     * Spawn a sky mob that belongs to this run: health multiplied by the floor's {@code hpScale}, damage by
     * {@code dmgScale}; its death reaches {@link BossFloor#onMobKill}. Removed when the run closes.
     */
    LivingEntity spawnMob(String mobId, Location at);

    /** Spawn the boss with an exact max health (e.g. floor().bossHp) and damage multiplier. Shown on the boss bar. */
    LivingEntity spawnBoss(String mobId, Location at, double maxHealth, double damageMultiplier);

    /**
     * Spawn a mob of this fight with an EXACT max health and damage per hit (research numbers per floor), tracked like
     * {@link #spawnMob}: its hits reach {@link BossFloor#onDamage}, its death {@link BossFloor#onMobKill}. (Part B.)
     */
    default LivingEntity spawnExact(String mobId, Location at, double maxHealth, double damage) {
        return spawnExact(mobId, at, maxHealth, damage, true);
    }

    /**
     * As {@link #spawnExact(String, Location, double, double)}. {@code addShare}: the mob's contact hit is {@code damage x
     * dungeons.boss-add-melee-share} (an add's research figure is its attack, not a swing every second; wave-4
     * endgame). Pass false when {@code damage} is already a contact number (Lurid's copies use the body's share). Health
     * is scaled by {@code boss-hp-scale} and the run's party-size scale either way.
     */
    LivingEntity spawnExact(String mobId, Location at, double maxHealth, double damage, boolean addShare);

    /** A non-mob entity (display, stand, frame) that must be removed when the run closes. (Part B.) */
    void addProp(org.bukkit.entity.Entity e);

    /** The boss bar every player of the run sees (title text in '&amp;' codes; progress 0..1). */
    void bossBar(String title, float progress, BossBar.Color color);

    void hideBossBar();

    /** Chat to every member. */
    void broadcast(String legacy);

    /** Title to every member. */
    void title(String title, String subtitle);

    /** "[BOSS] Name: line" to every member. */
    void bossSay(String bossName, String line);

    /** The boss is dead: end the run successfully. Idempotent. */
    void complete();

    void fail(String reason);

    /** Ticks (5-tick steps) since {@link BossFloor#start}. */
    long steps();

    /** Free per-run storage for the fight's own state. */
    Map<String, Object> state();
}
