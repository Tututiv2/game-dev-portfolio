package com.skyclone;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.core.ModuleList;
import com.skyclone.core.foundation.FoundationCore;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Boot order:
 * <ol>
 *   <li>foundation (dials, db, commands, requirements, profiles, economy, calendar) — fatal if it fails;</li>
 *   <li>items, combat, world, ui cores by reflection — a missing or failing one is logged, not fatal;</li>
 *   <li>every module in {@link ModuleList} order: enable(); a throwing module is logged with its full
 *       stack and skipped;</li>
 *   <li>postEnable() for every enabled module (same protection; a failure there disables it);</li>
 *   <li>afterModules() on each core class that declares it (world builds islands, ui spawns NPCs).</li>
 * </ol>
 * Shutdown runs it backwards: profiles are saved first (ProfileUnload while modules can still react),
 * then modules disable in reverse, then cores shut down in reverse, foundation last (drains the DB).
 */
public final class SkyPlugin extends JavaPlugin {

    /** Core areas booted by reflection after the foundation, in order. */
    public static final List<String> CORE_CLASSES = List.of(
            "com.skyclone.core.items.ItemsCore",
            "com.skyclone.core.combat.CombatCore",
            "com.skyclone.core.world.WorldCore",
            "com.skyclone.core.ui.UiCore");

    private final List<Class<?>> bootedCores = new ArrayList<>();
    private final Map<String, GameModule> enabled = new LinkedHashMap<>();
    private boolean foundationUp;

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();
        Sky.installPlugin(this);
        FoundationCore.BootReport report = FoundationCore.REPORT;
        report.enabled.clear();
        report.failed.clear();
        report.missing.clear();
        report.core.clear();

        // 1. foundation: without it nothing can persist, so failing here stops the plugin.
        try {
            FoundationCore.boot(this);
            foundationUp = true;
            report.core.put("foundation", "ok");
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "FOUNDATION FAILED TO BOOT - SkyClone is disabling itself. Nothing was loaded.", t);
            report.core.put("foundation", "FAILED");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2. the other cores
        for (String cls : CORE_CLASSES) {
            String area = shortName(cls);
            Class<?> c;
            try {
                c = Class.forName(cls);
            } catch (ClassNotFoundException e) {
                getLogger().warning("[boot] core " + area + " is not in this build (" + cls + ") - skipping");
                report.core.put(area, "missing");
                continue;
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "[boot] core " + area + " failed to load", t);
                report.core.put(area, "FAILED");
                continue;
            }
            if (invokeStatic(c, "boot", true)) {
                bootedCores.add(c);
                report.core.put(area, "ok");
            } else {
                report.core.put(area, "FAILED");
            }
        }

        // 3. modules: enable()
        List<GameModule> loaded = new ArrayList<>();
        for (String id : ModuleList.IDS) {
            String cls = ModuleList.className(id);
            GameModule m;
            try {
                Class<?> c = Class.forName(cls);
                Object o = c.getDeclaredConstructor().newInstance();
                if (!(o instanceof GameModule gm)) {
                    report.failed.put(id, cls + " does not implement GameModule");
                    getLogger().severe("[boot] module " + id + ": " + cls + " does not implement GameModule - skipped");
                    continue;
                }
                m = gm;
            } catch (ClassNotFoundException e) {
                // Not written yet: one plain line, not a failure (a FAILED module is a real bug).
                report.missing.add(id);
                getLogger().info("[boot] module " + id + " not present");
                continue;
            } catch (Throwable t) {
                report.failed.put(id, "constructor: " + rootMessage(t));
                getLogger().log(Level.SEVERE, "[boot] module " + id + " could not be constructed - skipped", t);
                continue;
            }
            long t0 = System.currentTimeMillis();
            try {
                m.enable();
                loaded.add(m);
                long ms = System.currentTimeMillis() - t0;
                if (ms > 2000) getLogger().info("[boot] module " + id + " took " + ms + "ms to enable");
            } catch (Throwable t) {
                report.failed.put(id, "enable: " + rootMessage(t));
                getLogger().log(Level.SEVERE, "[boot] module " + id + " threw from enable() - skipped. Its listeners/commands "
                        + "registered before the throw may still be live.", t);
            }
        }

        // 4. postEnable()
        for (GameModule m : loaded) {
            try {
                m.postEnable();
                enabled.put(m.id(), m);
                report.enabled.add(m.id());
            } catch (Throwable t) {
                report.failed.put(m.id(), "postEnable: " + rootMessage(t));
                getLogger().log(Level.SEVERE, "[boot] module " + m.id() + " threw from postEnable()", t);
                try { m.disable(); } catch (Throwable t2) { getLogger().log(Level.WARNING, "[boot] and its disable() threw too", t2); }
            }
        }

        // 5. afterModules() on cores that have it
        tryAfterModules(FoundationCore.class);
        for (Class<?> c : bootedCores) tryAfterModules(c);

        report.bootMillis = System.currentTimeMillis() - start;
        getLogger().info("==================================================");
        getLogger().info(" SkyClone booted in " + report.bootMillis + "ms");
        getLogger().info(" core: " + report.coreSummary());
        getLogger().info(" modules: " + report.enabled.size() + " enabled, " + report.failed.size() + " FAILED"
                + (report.failed.isEmpty() ? "" : " " + report.failed.keySet())
                + ", " + report.missing.size() + " not present");
        for (Map.Entry<String, String> f : report.failed.entrySet()) getLogger().severe("   - FAILED " + f.getKey() + ": " + f.getValue());
        getLogger().info(" content: " + FoundationCore.contentCounts());
        getLogger().info("==================================================");
    }

    @Override
    public void onDisable() {
        if (!foundationUp) return;
        // Profiles first, while every module is still alive to react to ProfileUnload.
        FoundationCore.beforeModulesDisable();

        List<GameModule> mods = new ArrayList<>(enabled.values());
        for (int i = mods.size() - 1; i >= 0; i--) {
            GameModule m = mods.get(i);
            try {
                m.disable();
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "[shutdown] module " + m.id() + " threw from disable()", t);
            }
        }
        enabled.clear();

        for (int i = bootedCores.size() - 1; i >= 0; i--) invokeStatic(bootedCores.get(i), "shutdown", false);
        bootedCores.clear();

        try {
            FoundationCore.shutdown();
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "[shutdown] foundation shutdown threw", t);
        }
        foundationUp = false;
    }

    /** Enabled modules by id (read-only view for diagnostics). */
    public Map<String, GameModule> enabledModules() { return java.util.Collections.unmodifiableMap(enabled); }

    // ---- reflection helpers ---------------------------------------------------------------------

    private boolean invokeStatic(Class<?> c, String method, boolean withPlugin) {
        String area = shortName(c.getName());
        try {
            Method m = withPlugin ? c.getMethod(method, JavaPlugin.class) : c.getMethod(method);
            if (!Modifier.isStatic(m.getModifiers())) {
                getLogger().severe("[boot] " + c.getName() + "." + method + " is not static - skipped");
                return false;
            }
            long t0 = System.currentTimeMillis();
            if (withPlugin) m.invoke(null, this); else m.invoke(null);
            long ms = System.currentTimeMillis() - t0;
            if (withPlugin) getLogger().info("[boot] core " + area + " " + method + " ok (" + ms + "ms)");
            return true;
        } catch (NoSuchMethodException e) {
            getLogger().severe("[boot] core " + area + " has no public static " + method + "(" + (withPlugin ? "JavaPlugin" : "") + ")");
            return false;
        } catch (InvocationTargetException e) {
            getLogger().log(Level.SEVERE, "[boot] core " + area + " " + method + " threw", e.getCause() == null ? e : e.getCause());
            return false;
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "[boot] core " + area + " " + method + " failed", t);
            return false;
        }
    }

    private void tryAfterModules(Class<?> c) {
        Method m;
        try {
            m = c.getMethod("afterModules");
        } catch (NoSuchMethodException e) {
            return;
        }
        if (!Modifier.isStatic(m.getModifiers())) return;
        try {
            long t0 = System.currentTimeMillis();
            m.invoke(null);
            getLogger().info("[boot] " + shortName(c.getName()) + ".afterModules ok (" + (System.currentTimeMillis() - t0) + "ms)");
        } catch (InvocationTargetException e) {
            getLogger().log(Level.SEVERE, "[boot] " + shortName(c.getName()) + ".afterModules threw", e.getCause() == null ? e : e.getCause());
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "[boot] " + shortName(c.getName()) + ".afterModules failed", t);
        }
    }

    private static String shortName(String cls) {
        String s = cls.substring(cls.lastIndexOf('.') + 1);
        return s.endsWith("Core") ? s.substring(0, s.length() - 4).toLowerCase(java.util.Locale.ROOT) : s;
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getClass().getSimpleName() + (r.getMessage() == null ? "" : ": " + r.getMessage());
    }
}
