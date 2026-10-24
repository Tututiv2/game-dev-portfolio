package com.skyclone.core.combat;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.CombatService;
import com.skyclone.api.loot.LootService;
import com.skyclone.api.mob.MobService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Boot class of the COMBAT core: CombatService, MobService, LootService.
 * Called by SkyPlugin by reflection: boot() after items, afterModules() once every module has
 * enabled (ghost cleanup, attack speed for online players), shutdown() on disable.
 */
public final class CombatCore {

    private CombatCore() {}

    static JavaPlugin plugin;
    static MobServiceImpl mobs;
    static CombatServiceImpl combat;
    static LootServiceImpl loot;
    static Indicators indicators;
    static CombatListener combatListener;
    private static final List<BukkitTask> tasks = new ArrayList<>();
    private static final List<Listener> listeners = new ArrayList<>();

    public static void boot(JavaPlugin p) {
        plugin = p;
        CombatKeys.init(p);
        CombatDials.init();

        mobs = new MobServiceImpl();
        combat = new CombatServiceImpl(mobs);
        loot = new LootServiceImpl();
        indicators = new Indicators(p);

        Sky.provide(MobService.class, mobs);
        Sky.provide(CombatService.class, combat);
        Sky.provide(LootService.class, loot);

        combatListener = new CombatListener(combat, mobs);
        register(p, combatListener);
        register(p, new MobListener(mobs));
        register(p, loot);
        register(p, new ItemStash());

        tasks.add(Bukkit.getScheduler().runTaskTimer(p, () -> safe("indicators", indicators::tick), 1L, 1L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(p, () -> safe("mob tick", mobs::tick), 5L, 5L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(p, () -> safe("spawner", mobs.spawner::tick), 40L, 20L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(p, () -> safe("purge", combatListener::purge), 1200L, 1200L));

        CombatCommands.register(combat, mobs, loot);
        ItemStash.register();
        p.getLogger().info("[combat] core booted: combat, mobs, loot");
    }

    /** After every module enabled: worlds exist now, so clear ghost sky mobs / indicators. */
    public static void afterModules() {
        int ghosts = 0;
        for (World w : Bukkit.getWorlds()) {
            try { ghosts += mobs.cleanGhosts(w.getEntities()); } catch (RuntimeException e) { /* world unloading */ }
        }
        if (ghosts > 0) Sky.log().info("[mobs] removed " + ghosts + " leftover sky mobs / indicators from a previous run");
        Sky.log().info("[mobs] " + mobs.all().size() + " mob types, " + mobs.spawner.states().size() + " spawn rules");
    }

    public static void shutdown() {
        for (BukkitTask t : tasks) {
            try { t.cancel(); } catch (RuntimeException ignored) { }
        }
        tasks.clear();
        for (Listener l : listeners) HandlerList.unregisterAll(l);
        listeners.clear();
        if (indicators != null) indicators.clear();
        if (mobs != null) mobs.removeAll();
    }

    private static void register(JavaPlugin p, Listener l) {
        Bukkit.getPluginManager().registerEvents(l, p);
        listeners.add(l);
    }

    private static final java.util.Map<String, Long> lastTaskError = new java.util.HashMap<>();

    /** Run a repeating task body; a throwing body is logged at most once a minute, never kills the task. */
    private static void safe(String what, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            Long last = lastTaskError.get(what);
            if (last == null || now - last > 60_000) {
                lastTaskError.put(what, now);
                plugin.getLogger().log(Level.WARNING, "[combat] " + what + " task threw (repeats suppressed for 60s)", t);
            }
        }
    }
}
