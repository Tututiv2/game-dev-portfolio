package com.skyclone.core.foundation;

import com.skyclone.api.config.Dials;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dials: code declares a key + default + description; {@code plugins/SkyClone/dials.yml} holds
 * only the values someone explicitly SET in game. A reset removes the override.
 *
 * <p>The known trap this guards against: a stale override in the file quietly beating a changed
 * code default. So the file never stores defaults, {@code /sb dial list} shows value AND default
 * side by side and marks overrides with *, and an override that equals the current default is
 * flagged too (it will stop tracking the default if the code changes).
 *
 * <p>Overrides for keys nobody registers (a removed module) are kept in the file, untouched.
 */
public final class DialsImpl implements Dials {

    public enum Kind { NUMBER, INTEGER, FLAG, TEXT }

    public final class DialImpl<T> implements Dial<T> {
        final String key;
        final Kind kind;
        final T def;
        final String description;
        volatile T value;
        volatile boolean overridden;
        final java.util.List<java.util.function.Consumer<T>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

        DialImpl(String key, Kind kind, T def, String description) {
            this.key = key; this.kind = kind; this.def = def; this.description = description == null ? "" : description;
            this.value = def;
        }

        @Override public String key() { return key; }
        @Override public T get() { return value; }
        @Override public T defaultValue() { return def; }
        @Override public String description() { return description; }
        public Kind kind() { return kind; }
        public boolean overridden() { return overridden; }

        @Override public void set(T v) {
            if (v == null) throw new IllegalArgumentException("dial " + key + ": null value");
            T old = value;
            value = v;
            overridden = true;
            raw.put(key, format(v));
            persist();
            notifyChange(old);
        }

        public void reset() {
            T old = value;
            value = def;
            overridden = false;
            raw.remove(key);
            persist();
            notifyChange(old);
        }

        @Override public void onChange(java.util.function.Consumer<T> listener) {
            if (listener != null) listeners.add(listener);
        }

        private void notifyChange(T old) {
            if (java.util.Objects.equals(old, value)) return;
            for (java.util.function.Consumer<T> l : listeners) {
                try { l.accept(value); }
                catch (Throwable t) { log.log(java.util.logging.Level.WARNING, "[dials] onChange listener of " + key + " threw", t); }
            }
        }

        /** Parse text in this dial's type and set it. Returns an error message or null. */
        @SuppressWarnings("unchecked")
        public String setFromText(String text) {
            Object parsed = parse(kind, text);
            if (parsed == null) return "'" + text + "' is not a valid " + kind.name().toLowerCase(Locale.ROOT);
            set((T) parsed);
            return null;
        }
    }

    private final Logger log;
    private final File file;
    private final Map<String, DialImpl<?>> dials = new ConcurrentHashMap<>();
    /** key -> override text as stored in the file (includes keys nobody registered). */
    private final Map<String, String> raw = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SkyClone-Dials-IO");
        t.setDaemon(true);
        return t;
    });

    public DialsImpl(File file, Logger log) {
        this.file = file;
        this.log = log;
        load();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            for (String k : y.getKeys(true)) {
                if (y.isConfigurationSection(k)) continue;
                Object v = y.get(k);
                if (v != null) raw.put(k, String.valueOf(v));
            }
            log.info("[dials] " + raw.size() + " override(s) loaded from " + file.getName());
        } catch (Exception e) {
            log.log(Level.SEVERE, "[dials] could not read " + file + " - using code defaults", e);
        }
    }

    // ---- Dials -----------------------------------------------------------------------------

    @Override public Dial<Double> number(String key, double def, String description) { return make(key, Kind.NUMBER, def, description); }
    @Override public Dial<Integer> integer(String key, int def, String description) { return make(key, Kind.INTEGER, def, description); }
    @Override public Dial<Boolean> flag(String key, boolean def, String description) { return make(key, Kind.FLAG, def, description); }
    @Override public Dial<String> text(String key, String def, String description) { return make(key, Kind.TEXT, def == null ? "" : def, description); }

    @SuppressWarnings("unchecked")
    private <T> Dial<T> make(String key, Kind kind, T def, String description) {
        if (key == null || key.isBlank() || key.contains(" ")) throw new IllegalArgumentException("bad dial key '" + key + "'");
        DialImpl<?> existing = dials.get(key);
        if (existing != null) {
            if (existing.kind == kind) {
                if (!java.util.Objects.equals(existing.def, def)) {
                    log.warning("[dials] " + key + " registered twice with different defaults (" + existing.def + " vs " + def + "); keeping the first");
                }
                return (Dial<T>) existing;
            }
            log.warning("[dials] " + key + " registered as " + existing.kind + " and again as " + kind + "; the second gets a private dial");
            return new DialImpl<>(key, kind, def, description);
        }
        DialImpl<T> d = new DialImpl<>(key, kind, def, description);
        String stored = raw.get(key);
        if (stored != null) {
            Object parsed = parse(kind, stored);
            if (parsed != null) {
                d.value = (T) parsed;
                d.overridden = true;
            } else {
                log.warning("[dials] override for " + key + " ('" + stored + "') is not a valid " + kind + "; using the default " + def);
            }
        }
        dials.put(key, d);
        return d;
    }

    public DialImpl<?> find(String key) { return key == null ? null : dials.get(key); }

    public Collection<DialImpl<?>> all() {
        return new ArrayList<>(new TreeMap<>(dials).values());
    }

    /** Overrides in the file whose key no code registers (stale). */
    public List<String> orphanOverrides() {
        List<String> out = new ArrayList<>();
        for (String k : new TreeMap<>(raw).keySet()) if (!dials.containsKey(k)) out.add(k);
        return out;
    }

    // ---- parsing / formatting (pure, static so tests can use them) -----------------------------

    public static Object parse(Kind kind, String text) {
        if (text == null) return null;
        String t = text.trim();
        switch (kind) {
            case NUMBER -> {
                double v = Coins.parse(t);
                return Double.isNaN(v) ? null : v;
            }
            case INTEGER -> {
                double v = Coins.parse(t);
                if (Double.isNaN(v) || v != Math.rint(v) || v > Integer.MAX_VALUE || v < Integer.MIN_VALUE) return null;
                return (int) v;
            }
            case FLAG -> {
                return switch (t.toLowerCase(Locale.ROOT)) {
                    case "true", "on", "yes", "1", "enable", "enabled" -> Boolean.TRUE;
                    case "false", "off", "no", "0", "disable", "disabled" -> Boolean.FALSE;
                    default -> null;
                };
            }
            case TEXT -> {
                return text;
            }
        }
        return null;
    }

    /** Value as text, without scientific notation for whole numbers. */
    public static String format(Object v) {
        if (v instanceof Double d) {
            if (d == Math.rint(d) && Math.abs(d) < 1e17) return Long.toString(d.longValue());
            return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }

    // ---- persistence ----------------------------------------------------------------------

    private final Object writeLock = new Object();

    private void persist() {
        Map<String, String> snapshot = new TreeMap<>(raw);
        try {
            io.execute(() -> write(snapshot));
        } catch (Exception e) {
            write(snapshot); // executor gone (shutdown): write inline
        }
    }

    private void write(Map<String, String> snapshot) {
        synchronized (writeLock) {
            try {
                YamlConfiguration y = new YamlConfiguration();
                y.options().setHeader(List.of(
                        "SkyClone dial OVERRIDES. Edit in game: /sb dial list | get | set | reset.",
                        "Only values someone explicitly set live here; everything else uses the code default.",
                        "A stale value here beats a changed default - /sb dial list shows both."));
                for (Map.Entry<String, String> e : snapshot.entrySet()) {
                    DialImpl<?> d = dials.get(e.getKey());
                    Object typed = d == null ? null : parse(d.kind, e.getValue());
                    y.set(e.getKey(), typed != null && d.kind != Kind.TEXT ? typed : e.getValue());
                }
                File parent = file.getAbsoluteFile().getParentFile();
                if (parent != null) parent.mkdirs();
                File tmp = new File(file.getPath() + ".tmp");
                Files.writeString(tmp.toPath(), y.saveToString(), StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                log.log(Level.SEVERE, "[dials] could not write " + file, e);
            }
        }
    }

    public void shutdown() {
        io.shutdown();
        try { io.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
