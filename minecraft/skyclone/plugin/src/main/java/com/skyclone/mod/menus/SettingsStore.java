package com.skyclone.mod.menus;

import com.skyclone.api.Sky;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player settings: member data {@code "settings"} (one field, {@code Map<String,String> values}),
 * mirrored onto the player's PDC ({@code skyclone:setting.<key>}) so other modules can read a value
 * without sharing the profile key. See {@link SettingDefs} for the contract.
 */
public final class SettingsStore {

    private SettingsStore() {}

    /** Gson-friendly. Exactly ONE field so a reader's class with the same field converts losslessly. */
    public static final class Data {
        public Map<String, String> values = new LinkedHashMap<>();
    }

    public static final String KEY = "settings";

    /** Players who turned player chat off; read from the async chat thread. */
    static final Set<UUID> CHAT_HIDDEN = ConcurrentHashMap.newKeySet();

    /** Players who turned Damage Numbers off; read when core spawns a damage indicator. */
    static final Set<UUID> INDICATORS_HIDDEN = ConcurrentHashMap.newKeySet();

    /**
     * Thread-safe copy of every ready player's values, for {@link SettingsService} readers that are off the
     * main thread (chat, async tasks). Refreshed by {@link #prepare}, {@link #set} and every main-thread
     * {@link #get}; dropped on quit.
     */
    static final java.util.Map<UUID, java.util.Map<String, String>> CACHE = new ConcurrentHashMap<>();

    /** Cached value (any thread), or null when the player has none cached. */
    static String cached(UUID id, String key) {
        java.util.Map<String, String> m = CACHE.get(id);
        return m == null ? null : m.get(key);
    }

    private static void cache(Player p, String key, String value) {
        if (p == null || key == null || value == null) return;
        CACHE.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>()).put(key, value);
    }

    static Data data(Player p) { return Sky.profiles().member(p, KEY, Data.class); }

    /** Value, or the default when unset / not ready / unknown key. */
    public static String get(Player p, String key) {
        SettingDefs.Setting s = SettingDefs.get(key);
        String def = s == null ? null : s.def();
        if (p == null || !Sky.profiles().isReady(p)) return def;
        Data d = data(p);
        if (s != null) adoptExternal(p, s, d);
        String v = d.values.get(key);
        if (v == null || (s != null && !s.valid(v))) return def;
        cache(p, key, v);
        return v;
    }

    /**
     * A setting another module owns may be changed by that module directly on the PDC mirror: the
     * island module sets {@code island.visitors} for every online co-op member when one of them picks
     * a mode in its own menu. For those keys a legal PDC value wins over member data, otherwise the
     * next {@link #prepare} would quietly put the old value back. Keys this module implements are
     * only ever written here, so member data stays authoritative for them.
     */
    private static void adoptExternal(Player p, SettingDefs.Setting s, Data d) {
        if ("menus".equals(s.reader())) return;
        String pdc = readPdc(p, s.key());
        if (pdc == null || !s.valid(pdc)) return;
        if (!pdc.equals(d.values.get(s.key()))) d.values.put(s.key(), pdc);
    }

    static String readPdc(Player p, String key) {
        try { return p.getPersistentDataContainer().get(pdcKey(key), PersistentDataType.STRING); }
        catch (Throwable t) { return null; }
    }

    public static boolean on(Player p, String key) { return "true".equals(get(p, key)); }

    /** Set a value (validated). Returns false for an unknown key or an illegal value. */
    public static boolean set(Player p, String key, String value) {
        SettingDefs.Setting s = SettingDefs.get(key);
        if (s == null || !s.valid(value) || !Sky.profiles().isReady(p)) return false;
        data(p).values.put(key, value);
        mirror(p, key, value);
        cache(p, key, value);
        if (key.equals(SettingDefs.CHAT_PLAYERS) || key.equals(SettingDefs.DAMAGE_NUMBERS)) syncChat(p);
        if (SidebarToggles.isToggle(key)) SidebarToggles.apply(p, key, true);
        for (java.util.function.BiConsumer<Player, String> l : LISTENERS) {
            try { l.accept(p, key); } catch (Throwable t) { Sky.log().warning("[menus] setting listener: " + t); }
        }
        return true;
    }

    /** Fill every missing key with its default, drop illegal values, mirror all to PDC. Call on ProfileReady. */
    static void prepare(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        Data d = data(p);
        for (SettingDefs.Setting s : SettingDefs.all()) {
            adoptExternal(p, s, d);
            String v = d.values.get(s.key());
            if (v == null || !s.valid(v)) { v = s.def(); d.values.put(s.key(), v); }
            mirror(p, s.key(), v);
            cache(p, s.key(), v);
        }
        syncChat(p);
        SidebarToggles.applyAll(p);
    }

    /** Reset every setting of a category (or all when null) to its default. */
    static void reset(Player p, SettingDefs.Category c) {
        for (SettingDefs.Setting s : SettingDefs.all()) if (c == null || s.category() == c) set(p, s.key(), s.def());
    }

    /** Mirrors the settings read on hot paths (chat, damage numbers) into their thread-safe sets. */
    static void syncChat(Player p) {
        if ("false".equals(get(p, SettingDefs.CHAT_PLAYERS))) CHAT_HIDDEN.add(p.getUniqueId());
        else CHAT_HIDDEN.remove(p.getUniqueId());
        if ("false".equals(get(p, SettingDefs.DAMAGE_NUMBERS))) INDICATORS_HIDDEN.add(p.getUniqueId());
        else INDICATORS_HIDDEN.remove(p.getUniqueId());
    }

    /** Called after any successful {@link #set} with (player, key); e.g. the profile snapshot re-takes on privacy changes. */
    static final java.util.List<java.util.function.BiConsumer<Player, String>> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    static void forget(Player p) {
        CHAT_HIDDEN.remove(p.getUniqueId());
        INDICATORS_HIDDEN.remove(p.getUniqueId());
        CACHE.remove(p.getUniqueId());
    }

    static NamespacedKey pdcKey(String key) {
        return new NamespacedKey("skyclone", "setting." + key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "_"));
    }

    private static void mirror(Player p, String key, String value) {
        try {
            PersistentDataContainer pdc = p.getPersistentDataContainer();
            pdc.set(pdcKey(key), PersistentDataType.STRING, value);
        } catch (Throwable t) {
            Sky.log().fine("[menus] could not mirror setting " + key + ": " + t);
        }
    }
}
