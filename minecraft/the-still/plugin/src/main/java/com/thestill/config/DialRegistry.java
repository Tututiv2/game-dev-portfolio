package com.thestill.config;

import com.thestill.core.AtomicFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every tunable in the plugin, in one place, discovered by construction rather
 * than by maintenance.
 *
 * <p>Registration is <b>order-independent</b>: overrides read from
 * {@code dials.yml} are held as pending values and applied to each dial the
 * moment it registers, whenever that happens. A service can therefore declare
 * its own dials in its own constructor — which is the point, because a dial that
 * isn't in {@code /config} the day it is written never gets added later.
 *
 * <p>The file is an <em>override layer only</em>. Defaults live in code, so the
 * two can never drift, and deleting a line restores the default rather than
 * producing a null.
 */
public final class DialRegistry {

    private final Logger log;
    private final File file;

    /** Insertion-ordered so the /config GUI lays out in declaration order. */
    private final Map<String, Dial<?>> dials = new LinkedHashMap<>();

    /** Raw leaf values from dials.yml, including keys nothing has registered yet. */
    private final Map<String, Object> overrides = new LinkedHashMap<>();

    public DialRegistry(Logger log, File file) {
        this.log = log;
        this.file = file;
    }

    // ---- declaring dials ----------------------------------------------

    public Builder<Double> doubleDial(String key, double def) {
        return new Builder<>(this, key, DialKind.DOUBLE, Double.class, def);
    }

    public Builder<Integer> intDial(String key, int def) {
        return new Builder<>(this, key, DialKind.INT, Integer.class, def);
    }

    public Builder<Long> longDial(String key, long def) {
        return new Builder<>(this, key, DialKind.LONG, Long.class, def);
    }

    public Builder<Boolean> boolDial(String key, boolean def) {
        return new Builder<>(this, key, DialKind.BOOLEAN, Boolean.class, def);
    }

    public Builder<String> stringDial(String key, String def) {
        return new Builder<>(this, key, DialKind.STRING, String.class, def);
    }

    private <T> Dial<T> register(Dial<T> dial) {
        if (dials.putIfAbsent(dial.key(), dial) != null) {
            throw new IllegalStateException("duplicate dial key: " + dial.key());
        }
        applyOverride(dial);
        return dial;
    }

    private void applyOverride(Dial<?> dial) {
        Object raw = overrides.get(dial.key());
        if (raw == null) {
            return;
        }
        String problem = dial.applyFromObject(raw);
        if (problem != null) {
            log.warning("dials.yml: ignoring " + dial.key() + " (" + problem
                    + "); using default " + dial.defaultValue());
        }
    }

    // ---- file I/O -------------------------------------------------------

    /**
     * Reads {@code dials.yml} into the pending-override map and applies it to
     * everything registered so far.
     *
     * <p>Safe to call before any dial exists — that is the normal case, since
     * this runs first and services register as they are constructed.
     */
    public void load() {
        overrides.clear();
        if (!file.isFile()) {
            log.info("no dials.yml yet; every dial is at its code default");
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String path : yaml.getKeys(true)) {
            Object value = yaml.get(path);
            if (value instanceof ConfigurationSection) {
                continue;
            }
            overrides.put(path, value);
        }
        dials.values().forEach(this::applyOverride);
        log.info("loaded " + overrides.size() + " dial override(s) from dials.yml");
    }

    /** Re-reads the file and re-applies it. Dials that actually change fire their listeners. */
    public int reload() {
        Map<String, Object> before = new LinkedHashMap<>();
        dials.forEach((key, dial) -> before.put(key, dial.get()));

        // Reset to code defaults first, so a line deleted from the file really
        // does go back to the default instead of keeping its last loaded value.
        dials.values().forEach(DialRegistry::resetToDefault);
        load();

        int changed = 0;
        for (Map.Entry<String, Dial<?>> entry : dials.entrySet()) {
            if (!entry.getValue().get().equals(before.get(entry.getKey()))) {
                changed++;
            }
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private static <T> void resetToDefault(Dial<T> dial) {
        dial.apply(dial.defaultValue());
    }

    /**
     * Writes every registered dial to {@code dials.yml} with its documentation
     * as comments, preserving any keys in the file that nothing has registered.
     */
    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();

        // Start from whatever is already on disk so unregistered keys survive.
        overrides.forEach(yaml::set);

        for (Dial<?> dial : dials.values()) {
            yaml.set(dial.key(), dial.toYaml());
            List<String> comment = new ArrayList<>(3);
            comment.add(dial.label() + " — " + dial.description());
            if (dial.kind().isNumeric()) {
                comment.add("default " + dial.defaultValue()
                        + " · range " + trim(dial.min()) + " to " + trim(dial.max())
                        + " · step " + trim(dial.step()));
            } else {
                comment.add("default " + dial.defaultValue());
            }
            yaml.setComments(dial.key(), comment);
        }

        yaml.options().setHeader(List.of(
                "The Still — tunable dials",
                "",
                "Generated. This file is an OVERRIDE LAYER: defaults live in code, so",
                "deleting a line restores the default rather than producing a null.",
                "Edit in game with /config, or edit here and run /still reload."));

        try {
            AtomicFiles.writeString(file, yaml.saveToString());
        } catch (IOException e) {
            log.log(Level.SEVERE, "could not write dials.yml", e);
        }
    }

    private static String trim(double d) {
        return d == Math.rint(d) && Double.isFinite(d)
                ? String.valueOf((long) d)
                : String.valueOf(d);
    }

    // ---- queries --------------------------------------------------------

    public Collection<Dial<?>> all() {
        return Collections.unmodifiableCollection(dials.values());
    }

    public Dial<?> byKey(String key) {
        return dials.get(key);
    }

    public int size() {
        return dials.size();
    }

    /** Grouped for the /config GUI, categories alphabetical, dials in declaration order. */
    public Map<String, List<Dial<?>>> byCategory() {
        Map<String, List<Dial<?>>> grouped = new TreeMap<>();
        for (Dial<?> dial : dials.values()) {
            grouped.computeIfAbsent(dial.category(), c -> new ArrayList<>()).add(dial);
        }
        return grouped;
    }

    // ---- builder --------------------------------------------------------

    public static final class Builder<T> {

        private final DialRegistry registry;
        private final String key;
        private final DialKind kind;
        private final Class<T> type;
        private final T def;

        private String label;
        private String category;
        private String description = "";
        private double step = 1.0;
        private Double min;
        private Double max;

        private Builder(DialRegistry registry, String key, DialKind kind, Class<T> type, T def) {
            this.registry = registry;
            this.key = key;
            this.kind = kind;
            this.type = type;
            this.def = def;
        }

        public Builder<T> label(String label) {
            this.label = label;
            return this;
        }

        public Builder<T> category(String category) {
            this.category = category;
            return this;
        }

        public Builder<T> describe(String description) {
            this.description = description;
            return this;
        }

        /** Increment for the /config GUI's +/- buttons. */
        public Builder<T> step(double step) {
            this.step = step;
            return this;
        }

        public Builder<T> range(double min, double max) {
            this.min = min;
            this.max = max;
            return this;
        }

        /**
         * Files the dial in the registry and returns it.
         *
         * <p>Numeric dials must declare a range. It costs twenty characters and
         * it forecloses the whole family of bugs where an admin types a value
         * nobody thought to bound — Shadow Prison shipped a rankup discount that
         * could exceed 100% and paid players to rank up.
         */
        public Dial<T> register() {
            if (kind.isNumeric() && (min == null || max == null)) {
                throw new IllegalStateException(
                        "numeric dial " + key + " must declare .range(min, max)");
            }
            if (min != null && min > max) {
                throw new IllegalStateException("dial " + key + " has min > max");
            }
            if (kind.isNumeric()) {
                double d = ((Number) def).doubleValue();
                if (!Double.isFinite(d)) {
                    throw new IllegalStateException("dial " + key + " has a non-finite default");
                }
                if (d < min || d > max) {
                    throw new IllegalStateException("dial " + key + " default " + d
                            + " is outside its own range " + min + ".." + max);
                }
            }
            Dial<T> dial = new Dial<>(
                    key,
                    label != null ? label : humanise(lastSegment(key)),
                    category != null ? category : humanise(firstSegment(key)),
                    description,
                    kind, type, def,
                    kind.isNumeric() ? step : 0.0,
                    min != null ? min : Double.NEGATIVE_INFINITY,
                    max != null ? max : Double.POSITIVE_INFINITY);
            return registry.register(dial);
        }

        private static String firstSegment(String key) {
            int dot = key.indexOf('.');
            return dot < 0 ? key : key.substring(0, dot);
        }

        private static String lastSegment(String key) {
            int dot = key.lastIndexOf('.');
            return dot < 0 ? key : key.substring(dot + 1);
        }

        private static String humanise(String segment) {
            String spaced = segment.replace('-', ' ').replace('_', ' ');
            return spaced.isEmpty()
                    ? spaced
                    : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1).toLowerCase(Locale.ROOT);
        }
    }
}
