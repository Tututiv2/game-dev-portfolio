package com.thestill.config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One tunable value.
 *
 * <p>A {@code Dial} cannot be constructed directly — the only way to get one is
 * {@link DialRegistry.Builder#register()}, which files it in the registry on the
 * way out. That is the design law ("every dial gets a /config GUI") made
 * structural: you cannot write a dial that the GUI doesn't know about, because
 * you cannot write a dial without registering it.
 *
 * <p>Services hold the {@code Dial} object and call {@link #get()} at use time,
 * never caching the value. That is what makes hot reload work everywhere at
 * once with no reload plumbing in any consumer.
 */
public final class Dial<T> {

    private final String key;
    private final String label;
    private final String category;
    private final String description;
    private final DialKind kind;
    private final Class<T> type;
    private final T defaultValue;

    /** Increment used by the /config GUI's +/- buttons. Zero for non-numeric dials. */
    private final double step;
    private final double min;
    private final double max;

    private volatile T value;
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();

    Dial(String key, String label, String category, String description,
         DialKind kind, Class<T> type, T defaultValue,
         double step, double min, double max) {
        this.key = key;
        this.label = label;
        this.category = category;
        this.description = description;
        this.kind = kind;
        this.type = type;
        this.defaultValue = defaultValue;
        this.step = step;
        this.min = min;
        this.max = max;
        this.value = defaultValue;
    }

    public T get() {
        return value;
    }

    public double asDouble() {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    public int asInt() {
        return value instanceof Number n ? n.intValue() : 0;
    }

    public long asLong() {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    public boolean asBoolean() {
        return value instanceof Boolean b && b;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String category() {
        return category;
    }

    public String description() {
        return description;
    }

    public DialKind kind() {
        return kind;
    }

    public T defaultValue() {
        return defaultValue;
    }

    public double step() {
        return step;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public boolean isDefault() {
        return value.equals(defaultValue);
    }

    /** Registers a callback fired whenever this dial's value actually changes. */
    public void onChange(Consumer<T> listener) {
        listeners.add(listener);
    }

    /**
     * Applies a new value, clamping numerics into range.
     *
     * @return true if the value changed
     */
    boolean apply(T incoming) {
        T clamped = clamp(incoming);
        if (clamped.equals(value)) {
            return false;
        }
        this.value = clamped;
        for (Consumer<T> listener : listeners) {
            listener.accept(clamped);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private T clamp(T incoming) {
        if (!kind.isNumeric()) {
            return incoming;
        }
        double raw = ((Number) incoming).doubleValue();
        double bounded = Math.clamp(raw, min, max);
        return (T) switch (kind) {
            case DOUBLE -> bounded;
            case INT -> (int) Math.round(bounded);
            case LONG -> Math.round(bounded);
            default -> incoming;
        };
    }

    /**
     * Parses and applies a value from text, as typed into the /config GUI or a
     * command.
     *
     * <p>Rejects rather than coerces. {@code Double.parseDouble} happily accepts
     * "NaN" and "Infinity", and {@code Math.max(0, NaN)} is NaN — that is how a
     * single {@code /bal give <p> NaN} permanently poisoned an account on Shadow
     * Prison. Non-finite input never gets past this method.
     *
     * @return null on success, or a human-readable reason for rejection
     */
    @SuppressWarnings("unchecked")
    public String applyFromString(String raw) {
        if (raw == null) {
            return "no value given";
        }
        String text = raw.trim();
        try {
            Object parsed = switch (kind) {
                case DOUBLE -> {
                    double d = Double.parseDouble(text);
                    if (!Double.isFinite(d)) {
                        yield null;
                    }
                    yield d;
                }
                case INT -> Integer.parseInt(text);
                case LONG -> Long.parseLong(text);
                case BOOLEAN -> switch (text.toLowerCase()) {
                    case "true", "yes", "on", "1" -> Boolean.TRUE;
                    case "false", "no", "off", "0" -> Boolean.FALSE;
                    default -> null;
                };
                case STRING -> text;
            };
            if (parsed == null) {
                return "not a valid " + kind.name().toLowerCase() + ": " + text;
            }
            apply((T) parsed);
            return null;
        } catch (NumberFormatException e) {
            return "not a number: " + text;
        }
    }

    /**
     * Coerces a value read out of YAML (where an int-looking double arrives as
     * Integer, and a large int as Long) into this dial's declared type.
     *
     * @return null on success, or a reason for rejection
     */
    @SuppressWarnings("unchecked")
    String applyFromObject(Object raw) {
        if (raw == null) {
            return "null";
        }
        if (kind.isNumeric()) {
            if (!(raw instanceof Number n)) {
                return "expected a number, got " + raw.getClass().getSimpleName();
            }
            double d = n.doubleValue();
            if (!Double.isFinite(d)) {
                return "non-finite number: " + d;
            }
            Object typed = switch (kind) {
                case DOUBLE -> d;
                case INT -> (int) Math.round(d);
                case LONG -> Math.round(d);
                default -> throw new IllegalStateException();
            };
            apply((T) typed);
            return null;
        }
        if (kind == DialKind.BOOLEAN) {
            if (!(raw instanceof Boolean b)) {
                return "expected true/false, got " + raw;
            }
            apply((T) b);
            return null;
        }
        apply((T) String.valueOf(raw));
        return null;
    }

    /** The value as it should be written back to YAML. */
    Object toYaml() {
        return value;
    }

    public Class<T> type() {
        return type;
    }

    @Override
    public String toString() {
        return key + "=" + value + (isDefault() ? " (default)" : " (default " + defaultValue + ")");
    }
}
