package com.skyclone.mod.mining;

import com.google.gson.Gson;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What can be mined where (data/mining/blocks.json). Pure Java: materials are plain upper-case names
 * so tests and the gen scripts share one table. FIRST matching entry wins, which is how the same
 * vanilla block means different things on different islands (cobblestone is Tungsten in the frozen
 * tunnels, gray wool is Hard Stone in the crystal caverns and Mithril in the Dwarven Mines).
 */
public final class BlockTable {

    public enum Kind {
        STANDARD, ORE, PURE_ORE, OBSIDIAN, METAL, GEMSTONE, HARD_STONE, STAR;

        /** Blocks that use the 60x instamine rule (research 2.3). */
        public boolean sixtyRule() { return this != STANDARD && this != HARD_STONE; }
    }

    public static final class Def {
        public String id;
        public String name;
        public Kind kind = Kind.STANDARD;
        public List<String> islands = new ArrayList<>();
        public List<String> materials = new ArrayList<>();
        public double strength;
        public int breakingPower;
        public String drop;
        public int min = 1;
        public int max = 1;
        public double xp;
        public String powder;
        public int powderAmount;
        public int quality = 1;
        public int fuel = 1;
        public String gem;
        public boolean areaOnly;

        public boolean isMithril() { return id != null && id.startsWith("mithril"); }
        public boolean isTitanium() { return "titanium".equals(id); }
        public boolean isGlaciteMetal() { return "glacite".equals(id) || "umber".equals(id) || "tungsten".equals(id); }
        public boolean isDwarvenMetal() { return kind == Kind.METAL; }
    }

    private static final class File { List<Def> blocks = new ArrayList<>(); }

    private final List<Def> defs;
    private final Map<String, Def> byId = new LinkedHashMap<>();
    /** island -> material -> def (first wins). */
    private final Map<String, Map<String, Def>> index = new HashMap<>();

    private BlockTable(List<Def> defs) {
        this.defs = defs;
        for (Def d : defs) {
            byId.putIfAbsent(d.id, d);
            for (String island : d.islands)
                for (String m : d.materials)
                    index.computeIfAbsent(island, k -> new HashMap<>()).putIfAbsent(m.toUpperCase(), d);
        }
    }

    public static BlockTable load(Reader r) {
        File f = new Gson().fromJson(r, File.class);
        return new BlockTable(f == null || f.blocks == null ? List.of() : f.blocks);
    }

    /** The def for a material on an island, or null when it is not mineable there. */
    public Def lookup(String island, String material) {
        if (island == null || material == null) return null;
        Map<String, Def> m = index.get(island);
        return m == null ? null : m.get(material.toUpperCase());
    }

    public Def byId(String id) { return byId.get(id); }

    public List<Def> all() { return defs; }

    public Set<String> islands() { return index.keySet(); }
}
