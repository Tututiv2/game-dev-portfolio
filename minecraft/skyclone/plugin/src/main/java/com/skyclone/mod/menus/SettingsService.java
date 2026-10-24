package com.skyclone.mod.menus;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * {@link Features.Settings} over this module's stored settings (core wave 5). Any module can read a player's
 * Settings value without touching the shared {@code settings} member key or the PDC mirror:
 * <pre>
 *   boolean on = Sky.service(Features.Settings.class).flag(p, "notify.bank_interest", true);
 * </pre>
 * <ul>
 *   <li><b>Main thread</b>: the authoritative value ({@link SettingsStore#get}): member data, with a legal value
 *       another module wrote onto the PDC mirror adopted first (island, mayors).</li>
 *   <li><b>Any other thread</b> (async chat, a DB callback): the per-player cache that {@code prepare / set / get}
 *       keep current - never a profile read off the main thread.</li>
 *   <li>A key this module does not define: the PDC mirror {@code skyclone:setting.<key>} on the main thread (a
 *       module that keeps its own key there still reads through this), else {@code def}.</li>
 *   <li>Profile not ready / null player / unparseable value: {@code def}, like the no-op.</li>
 * </ul>
 * Cost: a map lookup plus one PDC read for keys another module may write; nothing is allocated.
 */
final class SettingsService implements Features.Settings {

    @Override
    public boolean flag(Player p, String key, boolean def) {
        String v = text(p, key, null);
        if (v == null) return def;
        if (v.equalsIgnoreCase("true")) return true;
        if (v.equalsIgnoreCase("false")) return false;
        return def;
    }

    @Override
    public String text(Player p, String key, String def) {
        if (p == null || key == null) return def;
        String k = key.toLowerCase(java.util.Locale.ROOT);
        SettingDefs.Setting s = SettingDefs.get(k);
        if (!Bukkit.isPrimaryThread()) {
            String v = SettingsStore.cached(p.getUniqueId(), k);
            return v == null ? def : v;
        }
        try {
            if (s == null) {
                String v = SettingsStore.readPdc(p, k);
                return v == null || v.isBlank() ? def : v;
            }
            if (!Sky.profiles().isReady(p)) return def;
            String v = SettingsStore.get(p, k);
            return v == null ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }
}
