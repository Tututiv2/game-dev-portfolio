package com.skyclone.core.items;

import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.item.ItemService;
import com.skyclone.api.item.RecipeService;
import com.skyclone.api.player.VitalsService;
import com.skyclone.api.stat.StatService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

/**
 * Boot class of the ITEMS core: ItemService, RecipeService, StatService, VitalsService,
 * AbilityService. Called by SkyPlugin by reflection, after foundation (db, dials, commands,
 * profiles, requirements, economy) and before combat / world / ui.
 */
public final class ItemsCore {

    private ItemsCore() {}

    private static JavaPlugin plugin;
    private static ItemServiceImpl items;
    private static RecipeServiceImpl recipes;
    private static StatServiceImpl stats;
    private static VitalsServiceImpl vitals;
    private static AbilityServiceImpl abilities;
    private static DupeScanner dupes;
    private static ItemListener itemListener;
    private static BukkitTask task;
    private static int seconds;
    private static int tickCount;

    public static void boot(JavaPlugin p) {
        plugin = p;
        ItemsDials.register();
        Sky.db().migrate("core_items", 1,
                "CREATE TABLE IF NOT EXISTS items_renames (id TEXT PRIMARY KEY, name TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS items_dupe_log (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, "
                        + "item_id TEXT, item_uuid TEXT, kept_player TEXT, kept_where TEXT, other_player TEXT, other_where TEXT, "
                        + "kept_data TEXT, other_data TEXT, action TEXT)",
                "CREATE INDEX IF NOT EXISTS items_dupe_log_uuid ON items_dupe_log(item_uuid)");

        items = new ItemServiceImpl(p);
        items.loadAliases();
        items.registerVanilla();
        items.loadRenames();
        recipes = new RecipeServiceImpl(p, items);
        stats = new StatServiceImpl(items);
        vitals = new VitalsServiceImpl(items, stats);
        stats.attachVitals(vitals);
        abilities = new AbilityServiceImpl(items, vitals);
        dupes = new DupeScanner(items);

        Sky.provide(ItemService.class, items);
        Sky.provide(RecipeService.class, recipes);
        Sky.provide(StatService.class, stats);
        Sky.provide(VitalsService.class, vitals);
        Sky.provide(AbilityService.class, abilities);

        itemListener = new ItemListener(items, dupes);
        var pm = Bukkit.getPluginManager();
        pm.registerEvents(itemListener, p);
        pm.registerEvents(new VanillaGuardListener(items), p);
        pm.registerEvents(new PlayerStateListener(stats, vitals), p);
        pm.registerEvents(new AbilityListener(abilities), p);

        ItemsAdmin.register(items, stats, vitals);
        ItemsSelfTests.register(items, recipes, abilities);

        task = Bukkit.getScheduler().runTaskTimer(p, ItemsCore::tick, 1L, 1L);
        p.getLogger().info("[items] core up: items, recipes, stats, vitals, abilities");
    }

    /** After every module enabled: re-render whatever online players hold (covers /reload). */
    public static void afterModules() {
        items.clearTemplates();
        plugin.getLogger().info("[items] " + items.all().size() + " item defs, " + recipes.all().size() + " recipes, "
                + items.enchants().size() + " enchants, " + items.reforges().size() + " reforges");
        java.util.List<String> missing = abilities.missingHandlers();
        if (!missing.isEmpty()) plugin.getLogger().warning("[abilities] " + missing.size() + " abilities have no handler yet");
        for (Player pl : Bukkit.getOnlinePlayers()) {
            try {
                items.sweep(pl, false);
                items.refreshInventory(pl, null);
                stats.recompute(pl);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "[items] refresh of " + pl.getName() + " failed", e);
            }
        }
    }

    public static void shutdown() {
        if (task != null) { task.cancel(); task = null; }
        if (stats == null) return;
        for (Player pl : Bukkit.getOnlinePlayers()) {
            try {
                stats.resetAttributes(pl);
                pl.setHealthScaled(false);
            } catch (RuntimeException ignored) {}
        }
    }

    private static void tick() {
        tickCount++;
        try { itemListener.tick(); } catch (Throwable t) { items.warnThrottled("tick-items", "item tick: " + t); }
        try { stats.tick(); } catch (Throwable t) { items.warnThrottled("tick-stats", "stat tick: " + t); }
        try { vitals.tick(); } catch (Throwable t) { items.warnThrottled("tick-vitals", "vitals tick: " + t); }
        try { abilities.tick(); } catch (Throwable t) { items.warnThrottled("tick-abilities", "ability tick: " + t); }
        if (tickCount % 20 == 0) {
            seconds++;
            int every = ItemsDials.integer(ItemsDials.DUPE_SCAN_SECONDS, 30);
            if (every > 0 && seconds % every == 0) {
                try { dupes.scan(Bukkit.getOnlinePlayers(), "periodic"); }
                catch (Throwable t) { items.warnThrottled("tick-dupes", "dupe scan: " + t); }
            }
        }
    }

    // ---- package access for listeners -------------------------------------------------------

    static JavaPlugin plugin() { return plugin; }
    static ItemServiceImpl items() { return items; }
    static AbilityServiceImpl abilities() { return abilities; }
}
