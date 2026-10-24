package com.thestill.state;

import java.util.Objects;

/**
 * A typed, defaulted key into a {@link StateMap}.
 *
 * <p>This is the extension point the whole game hangs off. Regions and players
 * both carry a schema-free {@code StateMap}, so Phase 2's Din pressure, Phase
 * 4's room heat, Phase 6's PvP flag and Phase 8's Ledger each cost one
 * {@code StateKey} constant and zero schema migrations.
 *
 * <p>Defaults are non-null by construction. There is no such thing as an absent
 * value at the API surface — {@code get} always returns something usable, which
 * removes the entire null-check-or-NPE class of bug from every caller.
 */
public final class StateKey<T> {

    private final String id;
    private final Class<T> type;
    private final T def;

    private StateKey(String id, Class<T> type, T def) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.def = Objects.requireNonNull(def, "default value must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("state key id must not be blank");
        }
    }

    public static StateKey<Double> ofDouble(String id, double def) {
        if (!Double.isFinite(def)) {
            throw new IllegalArgumentException("non-finite default for " + id + ": " + def);
        }
        return new StateKey<>(id, Double.class, def);
    }

    public static StateKey<Long> ofLong(String id, long def) {
        return new StateKey<>(id, Long.class, def);
    }

    public static StateKey<Integer> ofInt(String id, int def) {
        return new StateKey<>(id, Integer.class, def);
    }

    public static StateKey<Boolean> ofBoolean(String id, boolean def) {
        return new StateKey<>(id, Boolean.class, def);
    }

    public static StateKey<String> ofString(String id, String def) {
        return new StateKey<>(id, String.class, def);
    }

    public String id() {
        return id;
    }

    public Class<T> type() {
        return type;
    }

    public T defaultValue() {
        return def;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StateKey<?> other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "StateKey[" + id + ":" + type.getSimpleName() + "]";
    }
}
