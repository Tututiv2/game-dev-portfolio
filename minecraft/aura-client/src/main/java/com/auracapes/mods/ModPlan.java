package com.auracapes.mods;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which mods can be on together. Pure: no Minecraft, no Fabric, no files, so it is unit-tested.
 *
 * <p><b>Why this exists at all.</b> Fabric refuses to start when an enabled mod depends on one that
 * is off ("Incompatible mods found!"), and that screen is the one place a preset button must never
 * lead. So every change goes through here first: switching a mod on also switches on what it needs,
 * and switching one off is refused while something that stays on still needs it.
 *
 * <p><b>Dependencies are matched by what a jar PROVIDES, not by its file.</b> A jar provides its own
 * id, its {@code provides} aliases and every mod nested inside it (jar-in-jar). That is the only way
 * to get Fabric API right (its ~50 modules all live inside one jar) and the only way to see that two
 * different jars can satisfy the same id (SkyHanni and Skyblocker both carry {@code render-chest}).
 * A dependency that no jar on disk provides at all is the loader's own business (minecraft, java,
 * fabricloader, mixinextras) and is ignored rather than reported as missing.
 */
public final class ModPlan {

    /** One jar in the mods folder, on or off. {@code key} is its file name without ".disabled". */
    public record Entry(String key, String id, String name, Set<String> provides, Set<String> depends,
                        boolean locked) {
    }

    /** The result of a change: the jars that end up on, and one line per decision worth telling. */
    public record Result(Set<String> on, List<String> notes, boolean refused) {
    }

    private ModPlan() {
    }

    /**
     * Ids {@code e} needs that no jar in {@code on} provides, limited to ids SOME jar in {@code all}
     * provides (the rest belong to the loader).
     */
    public static Set<String> missing(Entry e, Collection<Entry> on, Collection<Entry> all) {
        Set<String> out = new LinkedHashSet<>();
        for (String dep : e.depends()) {
            if (providedBy(dep, all).isEmpty()) {
                continue;
            }
            if (providedBy(dep, on).isEmpty()) {
                out.add(dep);
            }
        }
        return out;
    }

    /** Names of the jars in {@code on} (other than {@code target}) that would break without it. */
    public static List<String> neededBy(Entry target, Collection<Entry> on, Collection<Entry> all) {
        List<Entry> without = new ArrayList<>();
        for (Entry e : on) {
            if (!e.key().equals(target.key())) {
                without.add(e);
            }
        }
        List<String> out = new ArrayList<>();
        for (Entry e : without) {
            for (String dep : e.depends()) {
                if (target.provides().contains(dep) && providedBy(dep, without).isEmpty()) {
                    out.add(e.name());
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Applies a request on top of the current state.
     *
     * @param current keys that are on now (as of the next start)
     * @param turnOn  keys the user wants on
     * @param turnOff keys the user wants off
     */
    public static Result apply(Collection<Entry> all, Set<String> current, Set<String> turnOn, Set<String> turnOff) {
        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (Entry e : all) {
            byKey.put(e.key(), e);
        }
        List<String> notes = new ArrayList<>();
        Set<String> on = new LinkedHashSet<>(current);
        on.addAll(turnOn);

        for (String key : turnOff) {
            Entry e = byKey.get(key);
            if (e == null || !on.contains(key)) {
                continue;
            }
            if (e.locked()) {
                notes.add(e.name() + " stays on: Aura Client needs it.");
                continue;
            }
            on.remove(key);
        }

        // Close over dependencies: anything still on gets what it needs, even if that means
        // overriding a requested "off" - a game that starts beats a preset followed to the letter.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String key : List.copyOf(on)) {
                Entry e = byKey.get(key);
                if (e == null) {
                    continue;
                }
                for (String dep : missing(e, entries(on, byKey), all)) {
                    Entry provider = pickProvider(dep, all, turnOff);
                    on.add(provider.key());
                    changed = true;
                    notes.add((turnOff.contains(provider.key()) ? "Kept " : "Also turned on ")
                            + provider.name() + " - " + e.name() + " needs it.");
                }
            }
        }
        return new Result(on, notes, false);
    }

    /** A single switch, refused (not overridden) when it would break something that stays on. */
    public static Result toggle(Collection<Entry> all, Set<String> current, String key, boolean wantOn) {
        Entry target = null;
        for (Entry e : all) {
            if (e.key().equals(key)) {
                target = e;
            }
        }
        if (target == null) {
            return new Result(Set.copyOf(current), List.of("No mod called " + key + "."), true);
        }
        if (wantOn) {
            return apply(all, current, Set.of(key), Set.of());
        }
        if (target.locked()) {
            return new Result(Set.copyOf(current), List.of(target.name() + " can't be turned off: Aura Client needs it."), true);
        }
        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (Entry e : all) {
            byKey.put(e.key(), e);
        }
        List<String> users = neededBy(target, entries(current, byKey), all);
        if (!users.isEmpty()) {
            return new Result(Set.copyOf(current), List.of(target.name() + " is needed by "
                    + String.join(", ", users) + ". Turn " + (users.size() == 1 ? "that" : "those") + " off first."), true);
        }
        Set<String> on = new LinkedHashSet<>(current);
        on.remove(key);
        return new Result(on, List.of(), false);
    }

    private static List<Entry> entries(Collection<String> keys, Map<String, Entry> byKey) {
        List<Entry> out = new ArrayList<>();
        for (String k : keys) {
            Entry e = byKey.get(k);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<Entry> providedBy(String id, Collection<Entry> in) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : in) {
            if (e.provides().contains(id)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Prefer a provider the user did not ask to switch off, then the jar whose own id IS the dependency. */
    private static Entry pickProvider(String dep, Collection<Entry> all, Set<String> turnOff) {
        Entry best = null;
        int bestScore = -1;
        for (Entry e : providedBy(dep, all)) {
            int score = (turnOff.contains(e.key()) ? 0 : 2) + (e.id().equals(dep) ? 1 : 0);
            if (score > bestScore) {
                best = e;
                bestScore = score;
            }
        }
        return best;
    }
}
