package com.skyclone.api;

import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.combat.CombatService;
import com.skyclone.api.command.CommandService;
import com.skyclone.api.config.Dials;
import com.skyclone.api.data.Database;
import com.skyclone.api.economy.EconomyService;
import com.skyclone.api.item.ItemService;
import com.skyclone.api.item.RecipeService;
import com.skyclone.api.loot.LootService;
import com.skyclone.api.mob.MobService;
import com.skyclone.api.npc.NpcService;
import com.skyclone.api.player.VitalsService;
import com.skyclone.api.profile.ProfileService;
import com.skyclone.api.stat.StatService;
import com.skyclone.api.time.CalendarService;
import com.skyclone.api.ui.MenuService;
import com.skyclone.api.ui.SidebarService;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.world.WorldService;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * THE one door into the game. Every module reaches every other system through here and
 * never by importing another module's classes.
 *
 * <p>Two kinds of service live behind it:
 * <ul>
 *   <li><b>Core services</b> (items, stats, combat, mobs, worlds, menus...) are installed by
 *       {@code com.skyclone.core} before any module enables. They are always present; calling
 *       one before core boots throws, loudly.</li>
 *   <li><b>Feature services</b> (skills, collections, pets, sacks, mayors...) are installed by
 *       the module that owns them, via {@link #provide}. {@link #service} NEVER returns null:
 *       if the owning module is missing or has not enabled yet, you get a do-nothing proxy
 *       whose methods return 0 / false / null / empty. So a module that grants Farming XP
 *       compiles and runs even when the skills module is broken — it just grants nothing.</li>
 * </ul>
 *
 * <p>Resolve feature services at CALL time, not in your module's enable(): modules enable
 * in list order, so the one you need may not have provided itself yet when you start.
 */
public final class Sky {

    private Sky() {}

    private static final Map<Class<?>, Object> SERVICES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Object> NOOPS = new ConcurrentHashMap<>();
    private static volatile Plugin plugin;

    // ---- installation (core + modules) -------------------------------------------------

    /** Called once by core at boot. */
    public static void installPlugin(Plugin p) { plugin = p; }

    /** Register an implementation. Core does this for core services; a module does it for its own. */
    public static <T> void provide(Class<T> type, T impl) {
        Object prev = SERVICES.put(type, impl);
        if (prev != null && plugin != null) {
            plugin.getLogger().warning("Service " + type.getSimpleName() + " was provided twice; the later one wins");
        }
    }

    /** True when a real implementation (not the no-op) is installed. */
    public static boolean has(Class<?> type) { return SERVICES.containsKey(type); }

    /**
     * The installed implementation, or a no-op proxy. Never null.
     * Use for FEATURE services (skills, collections, pets...).
     */
    @SuppressWarnings("unchecked")
    public static <T> T service(Class<T> type) {
        Object s = SERVICES.get(type);
        if (s != null) return (T) s;
        return (T) NOOPS.computeIfAbsent(type, Sky::noop);
    }

    @SuppressWarnings("unchecked")
    private static <T> T core(Class<T> type) {
        Object s = SERVICES.get(type);
        if (s == null) throw new IllegalStateException("Core service " + type.getSimpleName()
                + " is not installed. Core must boot before modules call it.");
        return (T) s;
    }

    private static Object noop(Class<?> type) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "toString" -> "NoOp(" + type.getSimpleName() + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            if (m.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, m, args);
            Class<?> r = m.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            if (r == double.class) return 0.0;
            if (r == float.class) return 0f;
            if (r == short.class) return (short) 0;
            if (r == byte.class) return (byte) 0;
            if (r == char.class) return (char) 0;
            if (r == java.util.List.class) return java.util.List.of();
            if (r == java.util.Set.class) return java.util.Set.of();
            if (r == java.util.Map.class) return java.util.Map.of();
            if (r == java.util.Collection.class) return java.util.List.of();
            if (r == java.util.Optional.class) return java.util.Optional.empty();
            if (r == String.class) return "";
            return null;
        });
    }

    // ---- core services ----------------------------------------------------------------

    public static Plugin plugin() {
        if (plugin == null) throw new IllegalStateException("Plugin not installed yet");
        return plugin;
    }
    public static Logger log() { return plugin().getLogger(); }

    public static Database db()              { return core(Database.class); }
    public static ProfileService profiles()  { return core(ProfileService.class); }
    public static EconomyService economy()   { return core(EconomyService.class); }
    public static ItemService items()        { return core(ItemService.class); }
    public static RecipeService recipes()    { return core(RecipeService.class); }
    public static StatService stats()        { return core(StatService.class); }
    public static VitalsService vitals()     { return core(VitalsService.class); }
    public static CombatService combat()     { return core(CombatService.class); }
    public static AbilityService abilities() { return core(AbilityService.class); }
    public static MobService mobs()          { return core(MobService.class); }
    public static LootService loot()         { return core(LootService.class); }
    public static WorldService worlds()      { return core(WorldService.class); }
    public static NpcService npcs()          { return core(NpcService.class); }
    public static MenuService menus()        { return core(MenuService.class); }
    public static SidebarService sidebar()   { return core(SidebarService.class); }
    public static CommandService commands()  { return core(CommandService.class); }
    public static Dials dials()              { return core(Dials.class); }
    public static CalendarService calendar() { return core(CalendarService.class); }
    public static Requirements requirements(){ return core(Requirements.class); }
}
