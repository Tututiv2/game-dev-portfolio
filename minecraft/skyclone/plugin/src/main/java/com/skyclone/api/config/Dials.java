package com.skyclone.api.config;

/**
 * In-game tunables. OWNER'S RULE: anything a person might want to tune is a dial, and every dial
 * is viewable and editable in game with {@code /sb dial list [filter]}, {@code /sb dial get <key>},
 * {@code /sb dial set <key> <value>}, {@code /sb dial reset <key>}. Nobody edits YAML by hand.
 *
 * <p>Keys are "module.name" ("slayer.boss-hp-scale"). Values persist in plugins/SkyClone/dials.yml.
 * Read {@code dial.get()} at USE time, not once at enable, so a change takes effect live.
 */
public interface Dials {

    interface Dial<T> {
        String key();
        T get();
        void set(T value);
        T defaultValue();
        String description();

        /**
         * Called (on the thread that changed it - the main thread for /sb dial) with the new value
         * whenever set/reset actually changes it. For things that must be re-scheduled, e.g. a timer
         * period. Prefer reading {@link #get()} at use time when you can. (Added by the core gate.)
         */
        default void onChange(java.util.function.Consumer<T> listener) {}
    }

    Dial<Double> number(String key, double def, String description);

    Dial<Integer> integer(String key, int def, String description);

    Dial<Boolean> flag(String key, boolean def, String description);

    Dial<String> text(String key, String def, String description);
}
