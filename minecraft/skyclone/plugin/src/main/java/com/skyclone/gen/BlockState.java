package com.skyclone.gen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Block-state string helpers: {@code "oak_stairs[facing=north,half=bottom]"}.
 * All methods accept an optional "minecraft:" prefix and return strings without it.
 */
public final class BlockState {

    private BlockState() {}

    /** Strip "minecraft:", trim, lower-case the name part. Null/blank becomes "air". */
    public static String normalise(String s) {
        if (s == null) return "air";
        s = s.trim();
        if (s.isEmpty()) return "air";
        if (s.startsWith("minecraft:")) s = s.substring(10);
        int b = s.indexOf('[');
        String name = b < 0 ? s : s.substring(0, b);
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (!lower.equals(name)) s = lower + (b < 0 ? "" : s.substring(b));
        if (b >= 0 && !s.endsWith("]")) s = s + "]";
        if (s.endsWith("[]")) s = s.substring(0, s.length() - 2);
        return s;
    }

    /** "oak_stairs[facing=north]" -> "oak_stairs" */
    public static String name(String s) {
        if (s == null) return "air";
        if (s.startsWith("minecraft:")) s = s.substring(10);
        int b = s.indexOf('[');
        return b < 0 ? s : s.substring(0, b);
    }

    public static boolean hasProps(String s) { return s != null && s.indexOf('[') >= 0; }

    /** Property value or null. */
    public static String prop(String s, String key) {
        int b = s.indexOf('[');
        if (b < 0) return null;
        int e = s.lastIndexOf(']');
        if (e < 0) e = s.length();
        for (String part : s.substring(b + 1, e).split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(key)) return part.substring(eq + 1).trim();
        }
        return null;
    }

    public static Map<String, String> props(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        int b = s.indexOf('[');
        if (b < 0) return m;
        int e = s.lastIndexOf(']');
        if (e < 0) e = s.length();
        for (String part : s.substring(b + 1, e).split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) m.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return m;
    }

    /** Set (or add) one property. */
    public static String with(String s, String key, String value) {
        Map<String, String> m = props(s);
        m.put(key, value);
        return build(name(s), m);
    }

    public static String build(String name, Map<String, String> props) {
        if (props.isEmpty()) return name;
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }
}
