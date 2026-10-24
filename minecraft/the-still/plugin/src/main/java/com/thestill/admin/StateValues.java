package com.thestill.admin;

import com.thestill.state.StateKey;
import com.thestill.state.StateMap;

/**
 * Reading and writing state keys an admin has never heard of.
 *
 * <p>{@link StateMap#serialise()} is self-describing — {@code d:812.5},
 * {@code b:true}, {@code s:text} — which is what makes a <em>generic</em> state
 * editor possible: one page covers Phase 2's Din, Phase 4's heat, Phase 6's PvP
 * flag and every key anybody adds afterwards, with no further work. This class is
 * the small amount of type handling that page needs, and it is deliberately free
 * of Bukkit so it can be tested.
 *
 * <h2>The rule that matters: an existing key keeps its type</h2>
 * {@link StateMap#get} casts by the key's declared type and silently falls back to
 * the default when the stored object is a different class. So writing
 * {@code rank.index} back as a {@code Long} when the code declares it
 * {@code StateKey.ofInt} does not throw and does not warn — it reads as rank 0
 * forever, and the player is back at D1 with no error anywhere. Editing an existing
 * key therefore <b>reuses the prefix it already has</b> rather than re-inferring
 * one from what the admin typed, and only a brand-new key is inferred at all.
 */
public final class StateValues {

    public static final char DOUBLE = 'd';
    public static final char LONG = 'l';
    public static final char INT = 'i';
    public static final char BOOLEAN = 'b';
    public static final char STRING = 's';
    /** A row this build cannot decode. Shown, never silently rewritten. */
    public static final char UNKNOWN = '?';

    private StateValues() {
    }

    /** The type tag of a serialised value, or {@link #UNKNOWN}. */
    public static char typeOf(String encoded) {
        if (encoded == null || encoded.length() < 2 || encoded.charAt(1) != ':') {
            return UNKNOWN;
        }
        char tag = encoded.charAt(0);
        return switch (tag) {
            case DOUBLE, LONG, INT, BOOLEAN, STRING -> tag;
            default -> UNKNOWN;
        };
    }

    /** The value without its type tag. The whole string if there is no tag to strip. */
    public static String bodyOf(String encoded) {
        if (encoded == null) {
            return "";
        }
        return typeOf(encoded) == UNKNOWN ? encoded : encoded.substring(2);
    }

    /** What to call this type in front of somebody who is not a programmer. */
    public static String describe(char type) {
        return switch (type) {
            case DOUBLE -> "decimal";
            case LONG -> "whole number";
            case INT -> "whole number (32-bit)";
            case BOOLEAN -> "true / false";
            case STRING -> "text";
            default -> "unreadable";
        };
    }

    /**
     * Turns typed text into the object that type stores, or null if it will not do.
     *
     * <p>Booleans accept exactly {@code true} and {@code false} — see
     * {@link StateMap}, which refuses {@code Boolean.parseBoolean} for the same
     * reason: it maps every other string to a confident {@code false}, so a typo
     * would set the flag off rather than being rejected.
     */
    public static Object parse(char type, String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        try {
            return switch (type) {
                case DOUBLE -> {
                    double value = Double.parseDouble(text);
                    yield Double.isFinite(value) ? value : null;
                }
                case LONG -> Long.parseLong(text);
                case INT -> Integer.parseInt(text);
                case BOOLEAN -> switch (text.toLowerCase()) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    default -> null;
                };
                // Not trimmed: the stored value is whatever they typed, because a
                // key holding a formatting string cares about its spaces.
                case STRING -> raw;
                default -> null;
            };
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * The type to give a key that does not exist yet.
     *
     * <p>Only ever used for a new key. Whole numbers become {@code long} rather
     * than {@code int} because that is what most keys here are ({@link
     * com.thestill.role.Static#BALANCE}, timestamps, counters) — and if the guess is
     * wrong the key simply reads as its default, which is why the tile says so and
     * why an existing key is never re-inferred.
     */
    public static char infer(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
            return BOOLEAN;
        }
        try {
            Long.parseLong(text);
            return LONG;
        } catch (NumberFormatException notWhole) {
            // fall through
        }
        try {
            double value = Double.parseDouble(text);
            if (Double.isFinite(value)) {
                return DOUBLE;
            }
        } catch (NumberFormatException notANumber) {
            // fall through
        }
        return STRING;
    }

    /** {@code d:812.5} — the form the store holds and the editor shows. */
    public static String encode(char type, String body) {
        return type + ":" + body;
    }

    /**
     * Writes a value into a state bag.
     *
     * @return null if it landed, otherwise the reason it was refused — which is
     *         shown to the admin verbatim, because "that is not a number" is a
     *         better answer than a button that appears to do nothing
     */
    public static String apply(StateMap state, String id, char type, String raw) {
        if (id == null || id.isBlank()) {
            return "a state key needs a name";
        }
        Object value = parse(type, raw);
        if (value == null) {
            return "'" + raw + "' is not a " + describe(type);
        }
        try {
            switch (type) {
                case DOUBLE -> state.set(StateKey.ofDouble(id, 0.0), (Double) value);
                case LONG -> state.set(StateKey.ofLong(id, 0L), (Long) value);
                case INT -> state.set(StateKey.ofInt(id, 0), (Integer) value);
                case BOOLEAN -> state.set(StateKey.ofBoolean(id, false), (Boolean) value);
                case STRING -> state.set(StateKey.ofString(id, ""), (String) value);
                default -> {
                    return "this build cannot write a '" + type + "' value";
                }
            }
        } catch (IllegalArgumentException refused) {
            // StateMap enforces its own invariants (no NaN, no null). Report what it
            // said rather than translating it, so the two can never disagree.
            return refused.getMessage();
        }
        return null;
    }

    /**
     * Deletes a key.
     *
     * <p>The type is irrelevant — {@link StateMap#remove} works off the id alone —
     * so a key this build cannot decode can still be removed, which is the only way
     * to clear a value written by a version that no longer exists.
     */
    public static void remove(StateMap state, String id) {
        state.remove(StateKey.ofString(id, ""));
    }
}
