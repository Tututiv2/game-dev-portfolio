package com.thestill.state;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * A thread-safe, schema-free, dirty-tracked bag of typed values.
 *
 * <p>Values are held as live objects and only serialised at save time, so a hot
 * read ({@code heat} every tick, for every occupied region) is a map lookup and
 * a cast rather than a string parse.
 *
 * <p>The serialised form is <em>self-describing</em> — {@code "d:412.5"},
 * {@code "b:true"} — so a round trip needs no key registry and no schema table.
 * Storage stays a two-column key/value blob forever.
 */
public final class StateMap {

    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public StateMap() {
    }

    // ---- reads ---------------------------------------------------------

    /**
     * Never returns null. A missing value, or one whose stored type no longer
     * matches the key (a key whose type changed between plugin versions),
     * yields the key's default rather than throwing at a call site that has no
     * way to recover.
     */
    public <T> T get(StateKey<T> key) {
        Object raw = values.get(key.id());
        return key.type().isInstance(raw) ? key.type().cast(raw) : key.defaultValue();
    }

    public double getDouble(StateKey<Double> key) {
        return get(key);
    }

    public long getLong(StateKey<Long> key) {
        return get(key);
    }

    public boolean getBoolean(StateKey<Boolean> key) {
        return get(key);
    }

    public boolean has(StateKey<?> key) {
        return values.containsKey(key.id());
    }

    // ---- writes --------------------------------------------------------

    public <T> void set(StateKey<T> key, T value) {
        if (value == null) {
            throw new IllegalArgumentException("null value for " + key.id()
                    + "; use the key's default instead of storing null");
        }
        if (value instanceof Double d && !Double.isFinite(d)) {
            // The single most expensive numeric bug in Shadow Prison was a NaN
            // reaching a persisted balance, after which every downstream add
            // was NaN too and the account was unrecoverable. Refuse at the model,
            // not at each of the 49 call sites.
            throw new IllegalArgumentException("non-finite value for " + key.id() + ": " + d);
        }
        values.put(key.id(), value);
        dirty.set(true);
    }

    public void remove(StateKey<?> key) {
        if (values.remove(key.id()) != null) {
            dirty.set(true);
        }
    }

    /**
     * Atomically adds to a double and returns the new value, clamped into
     * {@code [min, max]}.
     *
     * <p>This is the primitive room heat is built on: deposit on block break,
     * bleed off on a timer. Clamping and finiteness are enforced here so no
     * caller can drive a region's heat negative or infinite, whatever it passes.
     */
    public double add(StateKey<Double> key, double delta, double min, double max) {
        if (!Double.isFinite(delta)) {
            throw new IllegalArgumentException("non-finite delta for " + key.id() + ": " + delta);
        }
        if (min > max) {
            throw new IllegalArgumentException("min " + min + " > max " + max + " for " + key.id());
        }
        Object updated = values.compute(key.id(), (id, current) -> {
            double base = current instanceof Double d && Double.isFinite(d) ? d : key.defaultValue();
            double next = base + delta;
            if (!Double.isFinite(next)) {
                next = base;
            }
            return Math.clamp(next, min, max);
        });
        dirty.set(true);
        return (Double) updated;
    }

    public double add(StateKey<Double> key, double delta) {
        return add(key, delta, Double.NEGATIVE_INFINITY, Double.MAX_VALUE);
    }

    // ---- dirty tracking ------------------------------------------------

    public boolean isDirty() {
        return dirty.get();
    }

    public void markDirty() {
        dirty.set(true);
    }

    /**
     * Clears the dirty flag and returns a serialised snapshot, or null if there
     * was nothing to write.
     *
     * <p>Order matters: the flag is cleared <em>before</em> the snapshot is
     * taken. A mutation racing this call then re-dirties the map and is picked
     * up by the next flush. Snapshotting first and clearing after would drop
     * that mutation on the floor, and it would look like a random, unreproducible
     * loss of one player's progress.
     */
    public Map<String, String> takeDirtySnapshot() {
        if (!dirty.getAndSet(false)) {
            return null;
        }
        return serialise();
    }

    // ---- serialisation -------------------------------------------------

    public Map<String, String> serialise() {
        Map<String, String> out = new LinkedHashMap<>(values.size() * 2);
        values.forEach((id, value) -> {
            String encoded = encode(value);
            if (encoded != null) {
                out.put(id, encoded);
            }
        });
        return out;
    }

    /**
     * Loads serialised values, skipping anything unparseable.
     *
     * <p>One malformed row must never abort the load. Shadow Prison lost live
     * player data exactly this way: a single bad line threw out of the load
     * loop, the partial cache stayed in memory, and shutdown wrote it back over
     * the good file.
     *
     * @param onBadValue called with (key, reason) for each skipped entry
     */
    public void load(Map<String, String> raw, BiConsumer<String, String> onBadValue) {
        if (raw == null) {
            return;
        }
        raw.forEach((id, encoded) -> {
            try {
                Object decoded = decode(encoded);
                if (decoded == null) {
                    onBadValue.accept(id, "unrecognised encoding: " + encoded);
                } else {
                    values.put(id, decoded);
                }
            } catch (RuntimeException e) {
                onBadValue.accept(id, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
        dirty.set(false);
    }

    static String encode(Object value) {
        return switch (value) {
            case Double d -> "d:" + d;
            case Long l -> "l:" + l;
            case Integer i -> "i:" + i;
            case Boolean b -> "b:" + b;
            case String s -> "s:" + s;
            case null, default -> null;
        };
    }

    static Object decode(String encoded) {
        if (encoded == null || encoded.length() < 2 || encoded.charAt(1) != ':') {
            return null;
        }
        String body = encoded.substring(2);
        return switch (encoded.charAt(0)) {
            case 'd' -> {
                double d = Double.parseDouble(body);
                yield Double.isFinite(d) ? d : null;
            }
            case 'l' -> Long.parseLong(body);
            case 'i' -> Integer.parseInt(body);
            // Not Boolean.parseBoolean: it maps every non-"true" string to false,
            // so genuinely corrupt data would load as a confident `false`.
            case 'b' -> switch (body) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                default -> null;
            };
            case 's' -> body;
            default -> null;
        };
    }

    public int size() {
        return values.size();
    }

    @Override
    public String toString() {
        return "StateMap" + values;
    }
}
