package com.skyclone.mod.fishing;

import com.skyclone.mod.fishing.FishingCatalog.Pool;
import com.skyclone.mod.fishing.FishingCatalog.PoolCreature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides WHICH sea creatures can bite at a spot (research 07 §3.3/§3.4). Pure Java: the fishing
 * listener fills a {@link Spot} from the world and the player, the menus fill one to show odds, and
 * the unit tests fill one by hand.
 *
 * <ol>
 *   <li>The BASE pool is the highest-priority base pool whose liquid, island and zone keywords match
 *       (Crimson lava beats nothing; the Bayou, the Galatea islands and the Workshop beat open water).</li>
 *   <li>Every matching ADD pool is merged in (Park night squid, Oasis, the quarry grubbers...).</li>
 *   <li>Creatures are filtered by Fishing level, their condition and any extra requirement.</li>
 *   <li>REPLACE pools (sharks, spooky, hotspot, Carrot Monarch, Mooshroom Diver...) are separate:
 *       each one, when its trigger holds, has a chance to swap the rolled creature.</li>
 * </ol>
 */
public final class PoolResolver {

    private final FishingCatalog cat;

    public PoolResolver(FishingCatalog cat) { this.cat = cat; }

    /** Everything about a cast that decides the pool. */
    public static final class Spot {
        public String island;
        /** Lower-case zone id + display name (keywords are searched in it). */
        public String zoneText = "";
        /** "water" or "lava" ("dirt" fishes like water). */
        public String liquid = "water";
        public int fishingLevel;
        public boolean night;
        /** Bait id used for this cast, or null. */
        public String bait;
        /** Item ids the player carries (for "item:X" triggers). */
        public Set<String> carried = new HashSet<>();
        public boolean festival;
        public boolean spooky;
        public boolean hotspot;
        /** The angler has a placed Chumcap Bucket of their own within reach (Mooshroom Diver trigger). */
        public boolean chumcap;
        /** Only worms bite (Worm Bait in the Hollows). */
        public boolean wormsOnly;
        /**
         * Height of the bobber (block Y), for "above:N" / "below:N" (the Flaming Worm only bites in the
         * Precursor lava above Y64). NaN = not known (menus, hand-built spots): height conditions pass.
         */
        public double y = Double.NaN;
        /** Extra requirement check for creatures that name one (e.g. a trophy title); null = all pass. */
        public Predicate<String> requirement;
    }

    /** A creature that can bite here, with its effective weight. */
    public record Option(String mob, double weight, int level, String pool) {}

    /** A replacement roll that is live for this spot. */
    public record Replacement(Pool pool, List<Option> options) {}

    // ---- pools --------------------------------------------------------------------------------

    public Pool basePool(Spot s) {
        Pool best = null;
        for (Pool p : cat.pools) {
            if (!p.isBase() || !matches(p, s)) continue;
            if (best == null || p.priority > best.priority) best = p;
        }
        return best;
    }

    public List<Pool> addPools(Spot s) {
        List<Pool> out = new ArrayList<>();
        for (Pool p : cat.pools) if (p.isAdd() && matches(p, s)) out.add(p);
        return out;
    }

    boolean matches(Pool p, Spot s) {
        String liquid = "dirt".equals(s.liquid) ? "water" : s.liquid;
        if (p.liquid != null && !p.liquid.equals(liquid)) return false;
        if (p.islands != null && !p.islands.isEmpty() && (s.island == null || !p.islands.contains(s.island))) return false;
        if (p.keywords != null && !p.keywords.isEmpty() && !anyKeyword(p.keywords, s.zoneText)) return false;
        return true;
    }

    static boolean anyKeyword(List<String> keys, String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String k : keys) if (!k.isEmpty() && t.contains(k.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    // ---- creatures ----------------------------------------------------------------------------

    /**
     * The creatures that can bite here with their base weights (level-, condition- and
     * requirement-filtered). Duplicates across pools keep the highest weight.
     */
    public List<Option> options(Spot s) {
        Map<String, Option> by = new LinkedHashMap<>();
        Pool base = basePool(s);
        List<Pool> pools = new ArrayList<>();
        if (base != null) pools.add(base);
        pools.addAll(addPools(s));
        for (Pool p : pools) {
            for (PoolCreature c : p.creatures) {
                if (!eligible(c, s)) continue;
                Option o = new Option(c.mob, c.weight, c.level, p.id);
                Option prev = by.get(c.mob);
                if (prev == null || prev.weight() < o.weight()) by.put(c.mob, o);
            }
        }
        List<Option> out = new ArrayList<>(by.values());
        if (s.wormsOnly) out.removeIf(o -> !o.mob().contains("worm"));
        return out;
    }

    /** Every creature in the base + add pools, ignoring level and conditions (the "here" guide). */
    public List<Option> allHere(Spot s) {
        Map<String, Option> by = new LinkedHashMap<>();
        Pool base = basePool(s);
        List<Pool> pools = new ArrayList<>();
        if (base != null) pools.add(base);
        pools.addAll(addPools(s));
        for (Pool p : pools)
            for (PoolCreature c : p.creatures) by.putIfAbsent(c.mob, new Option(c.mob, c.weight, c.level, p.id));
        return new ArrayList<>(by.values());
    }

    boolean eligible(PoolCreature c, Spot s) {
        if (s.fishingLevel < c.level) return false;
        if (c.cond != null && !c.cond.isBlank() && !cond(c.cond, s)) return false;
        return s.requirement == null || s.requirement.test(c.mob);
    }

    /** "night&bait:DARK_BAIT", "zone:goblin|precursor", "!day", "zone:precursor&above:64"... */
    public static boolean cond(String cond, Spot s) {
        for (String raw : cond.split("&")) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            boolean neg = part.startsWith("!");
            if (neg) part = part.substring(1);
            boolean v = atom(part, s);
            if (v == neg) return false;
        }
        return true;
    }

    static boolean atom(String a, Spot s) {
        if (a.equals("night")) return s.night;
        if (a.equals("day")) return !s.night;
        if (a.equals("festival")) return s.festival;
        if (a.equals("spooky")) return s.spooky;
        if (a.equals("hotspot")) return s.hotspot;
        if (a.equals("chumcap")) return s.chumcap;
        if (a.startsWith("bait:")) return a.substring(5).equals(s.bait);
        if (a.startsWith("item:")) return s.carried.contains(a.substring(5));
        if (a.startsWith("zone:")) return anyKeyword(List.of(a.substring(5).split("\\|")), s.zoneText);
        if (a.startsWith("island:")) return a.substring(7).equals(s.island);
        if (a.startsWith("above:")) return Double.isNaN(s.y) || s.y > num(a.substring(6));
        if (a.startsWith("below:")) return Double.isNaN(s.y) || s.y < num(a.substring(6));
        return false;
    }

    private static double num(String v) {
        try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }

    /** Replacement pools whose trigger holds here, each with its level-filtered creatures. */
    public List<Replacement> replacements(Spot s) {
        List<Replacement> out = new ArrayList<>();
        for (Pool p : cat.pools) {
            if (!p.isReplace()) continue;
            String liquid = "dirt".equals(s.liquid) ? "water" : s.liquid;
            if (p.liquid != null && !p.liquid.equals(liquid)) continue;
            if (p.islands != null && !p.islands.isEmpty() && (s.island == null || !p.islands.contains(s.island))) continue;
            if (p.trigger != null && !cond(p.trigger, s)) continue;
            List<Option> opts = new ArrayList<>();
            for (PoolCreature c : p.creatures) if (eligible(c, s)) opts.add(new Option(c.mob, c.weight, c.level, p.id));
            if (!opts.isEmpty()) out.add(new Replacement(p, opts));
        }
        return out;
    }

    // ---- weight modifiers (§3.3 table) --------------------------------------------------------

    /** What changes weights on this cast. */
    public static final class Mods {
        /** Common Hook: x1.25 on COMMON creatures. */
        public double commonWeight = 1.0;
        /** Whale / Light (day) / Dark (night) bait: x1.25 on creatures whose base weight is under 400. */
        public double rareWeight = 1.0;
        /** Per-creature multipliers (Puddle Jumper Hook x5, Squid Hat x2). */
        public Map<String, Double> mob = new HashMap<>();
        /** Rarity lookup for the Common Hook. */
        public java.util.function.Function<String, String> rarity = m -> "COMMON";
    }

    public static double weight(Option o, Mods m) {
        double w = o.weight();
        if (m == null) return w;
        if (m.commonWeight != 1.0 && "COMMON".equals(m.rarity.apply(o.mob()))) w *= m.commonWeight;
        if (m.rareWeight != 1.0 && o.weight() < 400) w *= m.rareWeight;
        Double x = m.mob.get(o.mob());
        if (x != null) w *= x;
        return w;
    }

    /** P(each creature | a sea creature bites), after modifiers, as fractions summing to 1. */
    public static Map<String, Double> odds(List<Option> opts, Mods m) {
        Map<String, Double> out = new LinkedHashMap<>();
        double total = 0;
        for (Option o : opts) total += weight(o, m);
        for (Option o : opts) out.put(o.mob(), total <= 0 ? 0 : weight(o, m) / total);
        return out;
    }
}
