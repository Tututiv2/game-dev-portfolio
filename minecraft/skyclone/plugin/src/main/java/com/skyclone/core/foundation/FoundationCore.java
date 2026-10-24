package com.skyclone.core.foundation;

import com.skyclone.api.Sky;
import com.skyclone.api.command.CommandService;
import com.skyclone.api.config.Dials;
import com.skyclone.api.data.Database;
import com.skyclone.api.economy.EconomyService;
import com.skyclone.api.item.ItemService;
import com.skyclone.api.item.RecipeService;
import com.skyclone.api.mob.MobService;
import com.skyclone.api.npc.NpcService;
import com.skyclone.api.profile.ProfileService;
import com.skyclone.api.time.CalendarService;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.world.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Boots the foundation: dials, database, commands, requirements, profiles, economy, calendar.
 * Called directly by SkyPlugin first; every other core area and every module depends on it.
 *
 * <p>Shutdown is two-phase: {@link #beforeModulesDisable()} fires ProfileUnload for everyone and
 * saves while modules are still alive; {@link #shutdown()} (last of all) saves anything modules
 * wrote in their own disable(), then drains and closes the database.
 */
public final class FoundationCore {

    private FoundationCore() {}

    /** Everything the foundation built, for its own admin/command classes. */
    record Parts(JavaPlugin plugin, DialsImpl dials, SqliteDatabase db, CommandServiceImpl commands, RequirementsImpl requirements,
                 ProfileServiceImpl profiles, EconomyServiceImpl economy, CalendarServiceImpl calendar, ProfileCommands profileCommands) {}

    /** Boot outcome, filled in by SkyPlugin, read by /sb info. */
    public static final class BootReport {
        public final List<String> enabled = new ArrayList<>();
        public final Map<String, String> failed = new LinkedHashMap<>();
        /** Modules named in ModuleList whose class is not in this build (not written yet) - not a failure. */
        public final List<String> missing = new ArrayList<>();
        public final Map<String, String> core = new LinkedHashMap<>();
        public long bootMillis;

        public String coreSummary() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : core.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.length() == 0 ? "(not booted)" : sb.toString();
        }
    }

    public static final BootReport REPORT = new BootReport();

    /** {@code server.display-name}: read it with {@code Sky.dials().text("server.display-name", "SkyClone", ...)} from anywhere. */
    public static volatile Dials.Dial<String> SERVER_NAME;

    private static Parts parts;
    private static FoundationAdmin admin;

    public static void boot(JavaPlugin plugin) {
        File data = plugin.getDataFolder();
        if (!data.exists() && !data.mkdirs()) throw new IllegalStateException("cannot create " + data);

        DialsImpl dials = new DialsImpl(new File(data, "dials.yml"), plugin.getLogger());
        Sky.provide(Dials.class, dials);
        SERVER_NAME = dials.text("server.display-name", "SkyClone",
                "The server's display name (sidebar title, messages). Working title - change it here, never in code");

        SqliteDatabase db = new SqliteDatabase(new File(data, "skyclone.db"), plugin.getLogger());
        db.open();
        Sky.provide(Database.class, db);

        CommandServiceImpl commands = new CommandServiceImpl(plugin, dials);
        Sky.provide(CommandService.class, commands);

        RequirementsImpl requirements = new RequirementsImpl(plugin.getLogger());
        Sky.provide(Requirements.class, requirements);

        ProfileServiceImpl profiles = new ProfileServiceImpl(plugin, db, dials);
        profiles.migrate();
        Sky.provide(ProfileService.class, profiles);

        EconomyServiceImpl economy = new EconomyServiceImpl(profiles, db, dials, plugin.getLogger());
        Sky.provide(EconomyService.class, economy);

        CalendarServiceImpl calendar = new CalendarServiceImpl(plugin, dials);
        Sky.provide(CalendarService.class, calendar);

        ProfileCommands profileCommands = new ProfileCommands(profiles, economy, dials);
        profileCommands.register(commands);

        parts = new Parts(plugin, dials, db, commands, requirements, profiles, economy, calendar, profileCommands);
        admin = new FoundationAdmin(parts);
        admin.register();

        Bukkit.getPluginManager().registerEvents(profiles, plugin);
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onQuit(PlayerQuitEvent e) {
                commands.forget(e.getPlayer().getUniqueId());
                profileCommands.forget(e.getPlayer().getUniqueId());
            }
        }, plugin);

        profiles.loadOnlinePlayers();
        profiles.startAutosave();
        calendar.start();
        commands.startupCheck();
        plugin.getLogger().info("[foundation] booted: database " + db.file().getName() + ", " + dials.all().size() + " dials");
    }

    /** After every module enabled: fallbacks for player commands a module may own instead. */
    public static void afterModules() {
        if (admin != null) admin.registerCalendarFallback();
    }

    /** Phase 1 of shutdown (SkyPlugin calls it before any module's disable()). */
    public static void beforeModulesDisable() {
        if (parts == null) return;
        try { parts.profiles().beforeModulesDisable(); }
        catch (Throwable t) { parts.plugin().getLogger().log(Level.SEVERE, "[foundation] pre-disable save failed", t); }
    }

    public static void shutdown() {
        if (parts == null) return;
        Parts p = parts;
        parts = null;
        admin = null;
        try { p.calendar().stop(); } catch (Throwable t) { p.plugin().getLogger().log(Level.WARNING, "[foundation] calendar stop", t); }
        try { p.profiles().shutdown(); } catch (Throwable t) { p.plugin().getLogger().log(Level.SEVERE, "[foundation] final save failed", t); }
        try { p.dials().shutdown(); } catch (Throwable t) { p.plugin().getLogger().log(Level.WARNING, "[foundation] dials flush", t); }
        p.db().close(30);
        p.plugin().getLogger().info("[foundation] database closed (" + p.db().completedCount() + " ok, " + p.db().failedCount() + " failed this run)");
    }

    /** "items 123, mobs 45, recipes 678, npcs 9, islands 3, warps 4" - skipping services not installed. */
    public static String contentCounts() {
        List<String> out = new ArrayList<>();
        try { if (Sky.has(ItemService.class)) out.add("items " + Sky.items().all().size()); } catch (Throwable ignored) { }
        try { if (Sky.has(MobService.class)) out.add("mobs " + Sky.mobs().all().size()); } catch (Throwable ignored) { }
        try { if (Sky.has(RecipeService.class)) out.add("recipes " + Sky.recipes().all().size()); } catch (Throwable ignored) { }
        try { if (Sky.has(NpcService.class)) out.add("npcs " + Sky.npcs().all().size()); } catch (Throwable ignored) { }
        try { if (Sky.has(WorldService.class)) out.add("islands " + Sky.worlds().islands().size() + ", warps " + Sky.worlds().warps().size()); } catch (Throwable ignored) { }
        return out.isEmpty() ? "(no content services installed)" : String.join(", ", out);
    }
}
