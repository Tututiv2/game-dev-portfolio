package com.skyclone.core.foundation;

import com.skyclone.api.ui.Msg;
import com.skyclone.api.util.Requirements;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Requirement keys, resolved by whichever module owns the prefix. Fails CLOSED: an unknown prefix
 * (a typo, or a module that failed to enable) is false and logged once, never silently true.
 *
 * <p>Empty / null key = no requirement = true. Parts are joined with '&amp;' and all must hold.
 */
public final class RequirementsImpl implements Requirements {

    private final Logger log;
    private final Map<String, Resolver> resolvers = new ConcurrentHashMap<>();
    private final Set<String> warnedUnknown = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedThrow = ConcurrentHashMap.newKeySet();

    public RequirementsImpl(Logger log) { this.log = log; }

    @Override
    public void register(String prefix, Resolver resolver) {
        if (prefix == null || resolver == null) return;
        String p = prefix.toLowerCase(Locale.ROOT).trim();
        Resolver prev = resolvers.putIfAbsent(p, resolver);
        if (prev != null) log.warning("[requirements] prefix '" + p + "' registered twice; keeping the first");
    }

    public Set<String> prefixes() { return new java.util.TreeSet<>(resolvers.keySet()); }

    @Override public boolean known(String prefix) { return prefix != null && resolvers.containsKey(prefix.toLowerCase(Locale.ROOT)); }

    static List<String[]> parse(String key) {
        List<String[]> out = new ArrayList<>();
        if (key == null) return out;
        for (String part : key.split("&")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            String[] parts = t.split(":", -1);
            for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
            parts[0] = parts[0].toLowerCase(Locale.ROOT);
            out.add(parts);
        }
        return out;
    }

    private boolean checkPart(Player p, String[] parts) {
        Resolver r = resolvers.get(parts[0]);
        if (r == null) {
            if (warnedUnknown.add(parts[0])) {
                log.warning("[requirements] unknown prefix '" + parts[0] + "' in '" + String.join(":", parts)
                        + "' - treated as NOT met (fail closed). Is the owning module enabled?");
            }
            return false;
        }
        try {
            return r.check(p, parts);
        } catch (Throwable t) {
            if (warnedThrow.add(String.join(":", parts))) {
                log.log(Level.WARNING, "[requirements] resolver for '" + String.join(":", parts) + "' threw - treated as NOT met", t);
            }
            return false;
        }
    }

    private String describePart(String[] parts) {
        Resolver r = resolvers.get(parts[0]);
        if (r == null) return "&c" + String.join(":", parts);
        try {
            String d = r.describe(parts);
            return d == null || d.isBlank() ? "&a" + String.join(":", parts) : d;
        } catch (Throwable t) {
            return "&a" + String.join(":", parts);
        }
    }

    @Override
    public boolean check(Player p, String key) {
        for (String[] part : parse(key)) if (!checkPart(p, part)) return false;
        return true;
    }

    @Override
    public String describe(String key) {
        List<String[]> parts = parse(key);
        if (parts.isEmpty()) return "&aNothing";
        List<String> d = new ArrayList<>();
        for (String[] part : parts) d.add(describePart(part));
        return joinAnd(d);
    }

    static String joinAnd(List<String> in) {
        // A key merged from two sources (an island's requirement plus its warp's) can name the same thing
        // twice: "Requires Farming Skill 1 and Farming Skill 1!" at the Barn pad (wave-3 playtest).
        List<String> d = new ArrayList<>(new java.util.LinkedHashSet<>(in));
        if (d.size() == 1) return d.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.size(); i++) {
            if (i > 0) sb.append(i == d.size() - 1 ? "&c and " : "&c, ");
            sb.append(d.get(i));
        }
        return sb.toString();
    }

    /** Describes only the parts that FAIL for this player. */
    public String describeMissing(Player p, String key) {
        List<String> d = new ArrayList<>();
        for (String[] part : parse(key)) if (!checkPart(p, part)) d.add(describePart(part));
        return d.isEmpty() ? "" : joinAnd(d);
    }

    @Override
    public boolean checkOrTell(Player p, String key) {
        String missing = describeMissing(p, key);
        if (missing.isEmpty()) return true;
        if (p != null) Msg.error(p, "Requires " + missing + "&c!");
        return false;
    }
}
