package com.shadowprison.dial;

import com.shadowprison.money.Money;
import org.bukkit.Material;

/**
 * One live-tunable value. Every kind is stored as a {@code long}: booleans 0/1, money in cents,
 * percentages in basis points (10000 = 100%). No floating point, so no NaN dial either.
 *
 * <p>Create through {@link DialRegistry#register} — registering IS creating its /config entry, so a
 * tunable without in-game UI cannot exist (owner rule: "every new tunable gets /config UI").
 */
public final class Dial {

    public enum Kind { NUMBER, TOGGLE, MONEY, PERCENT }

    private final String key;
    private final String category;
    private final String label;
    private final String description;
    private final Kind kind;
    private final long defaultValue;
    private final long min;
    private final long max;
    private final long step;
    private final Material icon;
    private volatile long value;

    Dial(String key, String category, String label, String description, Kind kind,
         long defaultValue, long min, long max, long step, Material icon) {
        if (!key.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("dial key must be [a-z0-9_.-]: " + key);
        }
        if (min > max || defaultValue < min || defaultValue > max) {
            throw new IllegalArgumentException("dial " + key + " default outside [min,max]");
        }
        this.key = key;
        this.category = category;
        this.label = label;
        this.description = description;
        this.kind = kind;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        this.icon = icon;
        this.value = defaultValue;
    }

    public long get() {
        return value;
    }

    public boolean on() {
        return value != 0;
    }

    /** Clamped to [min, max]. Returns the value actually stored. */
    long set(long requested) {
        value = Math.max(min, Math.min(max, requested));
        return value;
    }

    public String format(long v) {
        return switch (kind) {
            case TOGGLE -> v != 0 ? "ON" : "OFF";
            case MONEY -> Money.format(v);
            case PERCENT -> (v / 100) + (v % 100 == 0 ? "" : "." + String.format("%02d", Math.abs(v % 100))) + "%";
            case NUMBER -> String.format(java.util.Locale.ROOT, "%,d", v);
        };
    }

    /** Parses owner input for this dial's kind; null if unreadable. */
    public Long parse(String raw) {
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            return switch (kind) {
                case TOGGLE -> switch (s) {
                    case "on", "true", "yes", "1" -> 1L;
                    case "off", "false", "no", "0" -> 0L;
                    default -> null;
                };
                case MONEY -> Money.parse(s).orElse(null);
                case PERCENT -> {
                    var cents = Money.parse(s.replace("%", ""));   // "12.5" -> 1250 basis points
                    yield cents.orElse(null);
                }
                case NUMBER -> Long.parseLong(s.replace(",", "").replace("_", ""));
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String key() { return key; }
    public String category() { return category; }
    public String label() { return label; }
    public String description() { return description; }
    public Kind kind() { return kind; }
    public long defaultValue() { return defaultValue; }
    public long min() { return min; }
    public long max() { return max; }
    public long step() { return step; }
    public Material icon() { return icon; }
}
