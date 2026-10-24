package com.skyclone.mod.soulflow;

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code data/soulflow/soulflow.json}: what Soulstream (reference "Soulflow") is made of, what holds it, what burns it.
 * Pure (Gson only, no Bukkit) so the unit tests read the shipping file. Every number is from the research / NEU lore;
 * see the file's {@code _doc}.
 */
public final class SoulflowConfig {

    /** A Soulstream accessory tier: when leaving combat it turns up to {@code units} into {@code overflow} Overflow Mana. */
    public static final class Tier {
        public String item;
        public int rank;
        public int units;
        public double overflow;
    }

    /** A flat % boost to every Overflow conversion while a requirement holds (End Portal Fumes: +30%). */
    public static final class Boost {
        public String id;
        public double percent;
        public String requirement;
    }

    /** Something that burns Soulstream on a clock while the player is in combat. */
    public static final class Drain {
        public String id;
        /** Accessory that must be carried / in the bag (null when a set drives it). */
        public String item;
        /** Armour set id + pieces (Final Destination). */
        public String set;
        public int pieces = 4;
        public boolean sneaking;
        public long cost = 1;
        /** Seconds between charges. */
        public int every = 5;
        /** Stats granted while paid (stat enum names), empty for drains whose bonus another module grants. */
        public Map<String, Double> stats = new LinkedHashMap<>();
        public boolean toggle;
        /** Toggle default (reference: the two accessories ship "Enabled: Off"). */
        @com.google.gson.annotations.SerializedName("default")
        public boolean defaultOn;
        public String label;
        /**
         * The bonus is granted by ANOTHER module (slayer's Vivid Darkness). Charging it before that module checks
         * Soulstream would make holding Soulstream a pure penalty, so it is charged only with dial soulflow.charge-owner-gated-drains.
         */
        public boolean ownerGated;
    }

    /** An ability that costs Soulstream. Matched by ability id first, else by (item id, trigger). */
    public static final class Cost {
        public String ability;
        public List<String> items = new ArrayList<>();
        public String trigger;
        public long cost;
        public String label;
        /** "hard" (default): refused when short. "soft": charged after the fact when possible (see hand-off). */
        public String gate = "hard";
        /** Only charged when the item's ItemData.extra has this key with a positive int (Phasewarp Merger). */
        public String whenExtra;

        public boolean soft() { return "soft".equalsIgnoreCase(gate); }
    }

    public Map<String, Long> currency = new LinkedHashMap<>();
    public List<Tier> tiers = new ArrayList<>();
    public List<Boost> overflowBoosts = new ArrayList<>();
    public List<Drain> drains = new ArrayList<>();
    public List<Cost> costs = new ArrayList<>();

    // ---- lookups (built by index()) ----------------------------------------------------------------
    private transient Map<String, Cost> byAbility;
    private transient Map<String, Cost> byItemTrigger;

    public static SoulflowConfig read(Reader r) {
        SoulflowConfig c = new Gson().fromJson(r, SoulflowConfig.class);
        if (c == null) c = new SoulflowConfig();
        c.index();
        return c;
    }

    public static SoulflowConfig read(InputStream in) {
        if (in == null) throw new IllegalStateException("data/soulflow/soulflow.json is missing from the jar");
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return read(r);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read soulflow.json: " + e, e);
        }
    }

    void index() {
        if (currency == null) currency = new LinkedHashMap<>();
        if (tiers == null) tiers = new ArrayList<>();
        if (overflowBoosts == null) overflowBoosts = new ArrayList<>();
        if (drains == null) drains = new ArrayList<>();
        if (costs == null) costs = new ArrayList<>();
        tiers.sort(Comparator.comparingInt(t -> t.rank));
        byAbility = new LinkedHashMap<>();
        byItemTrigger = new LinkedHashMap<>();
        for (Cost c : costs) {
            if (c == null) continue;
            if (c.items == null) c.items = new ArrayList<>();
            if (c.ability != null) byAbility.putIfAbsent(c.ability, c);
            if (c.trigger != null) for (String it : c.items) byItemTrigger.putIfAbsent(key(it, c.trigger), c);
        }
        for (Drain d : drains) if (d.stats == null) d.stats = new LinkedHashMap<>();
    }

    private static String key(String item, String trigger) { return item + "|" + trigger.toUpperCase(Locale.ROOT); }

    /**
     * The Soulstream cost of one ability on one item, or null. Ability id first (exact), then the item + trigger,
     * so a module that re-registers a katana under its own ability id is still charged.
     */
    public Cost costFor(String abilityId, String itemId, String trigger) {
        if (byAbility == null) index();
        Cost c = abilityId == null ? null : byAbility.get(abilityId);
        if (c != null) return c;
        if (itemId == null || trigger == null) return null;
        return byItemTrigger.get(key(itemId, trigger));
    }

    /** Units of Soulstream one item of this id internalizes to (0 = not a Soulstream item). */
    public long unitsOf(String itemId) {
        Long v = itemId == null ? null : currency.get(itemId);
        return v == null ? 0 : v;
    }

    public Tier tier(String itemId) {
        for (Tier t : tiers) if (t.item.equals(itemId)) return t;
        return null;
    }

    public Drain drain(String id) {
        for (Drain d : drains) if (d.id.equals(id)) return d;
        return null;
    }
}
