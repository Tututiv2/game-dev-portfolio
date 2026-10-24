package com.skyclone.mod.hunting;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every shard and habitat, indexed. Pure (Gson, no Bukkit): the unit tests load the same JSON the game does.
 */
public final class ShardCatalog {

    /** A place critters live: an island + a named area of its layout. */
    public static final class Habitat {
        public String id;
        public String island;
        public String area;
        /** land / water / combat. */
        public String kind;
        public String name;
        public boolean safari;
        public List<String> critters = new ArrayList<>();
    }

    private final Map<String, ShardDef> byId = new LinkedHashMap<>();
    private final Map<String, ShardDef> byAttr = new HashMap<>();
    private final Map<String, ShardDef> byCode = new HashMap<>();
    private final Map<String, ShardDef> byCritter = new HashMap<>();
    /** Our display name and attribute name, normalized by {@link #key}: "Inklotl", "Bucket Greed". */
    private final Map<String, ShardDef> byName = new HashMap<>();
    private final Map<String, List<ShardDef>> byMob = new HashMap<>();
    private final Map<String, List<ShardDef>> lines = new HashMap<>();
    private final Map<String, Habitat> habitats = new LinkedHashMap<>();

    public static ShardCatalog load(Reader shards, Reader habitats) {
        Gson g = new Gson();
        List<ShardDef> list = g.fromJson(shards, new TypeToken<List<ShardDef>>() {}.getType());
        List<Habitat> hs = habitats == null ? List.of() : g.fromJson(habitats, new TypeToken<List<Habitat>>() {}.getType());
        return new ShardCatalog(list == null ? List.of() : list, hs == null ? List.of() : hs);
    }

    public ShardCatalog(List<ShardDef> shards, List<Habitat> hs) {
        for (ShardDef s : shards) {
            if (s == null || s.id == null) continue;
            if (s.effect == null) s.effect = new ShardDef.Effect();
            byId.put(s.id, s);
            if (s.attr != null) byAttr.put(s.attr.toLowerCase(Locale.ROOT), s);
            if (s.code != null) byCode.put(s.code, s);
            if (s.name != null && !key(s.name).isEmpty()) byName.putIfAbsent(key(s.name), s);
            for (ShardDef.Source src : s.sources) {
                if ("critter".equals(src.type) && src.mob != null) byCritter.put(src.mob, s);
                if ("mob".equals(src.type)) for (String m : src.mobs) byMob.computeIfAbsent(m, k -> new ArrayList<>()).add(s);
            }
            lines.computeIfAbsent(s.rarity, k -> new ArrayList<>()).add(s);
        }
        // attribute names second, so a shard's own name always wins over another shard's attribute name
        for (ShardDef s : byId.values())
            if (s.attrName != null && !key(s.attrName).isEmpty()) byName.putIfAbsent(key(s.attrName), s);
        for (List<ShardDef> l : lines.values()) l.sort(Comparator.comparingInt(x -> x.num));
        for (Habitat h : hs) if (h != null && h.id != null) habitats.put(h.id, h);
    }

    public ShardDef get(String id) { return id == null ? null : byId.get(id.toUpperCase(Locale.ROOT)); }

    /**
     * By shard id, attribute key, code, or what a player SEES ("SHARD_GROVE", "nature_elemental", "C1", "Inklotl",
     * "SHARD_INKLOTL", "Bucket Greed", "bucket_greed", "king minos"). Ids stay the reference game's, so the display
     * name often differs from the id ({@code SHARD_SEPIALOT} is the Inklotl): an admin typing the name a menu shows
     * used to get "Unknown critter" (playtest wave 5). Ids win over names, so a name can never shadow an id.
     */
    public ShardDef find(String any) {
        if (any == null || any.isBlank()) return null;
        any = any.trim();
        ShardDef s = get(any);
        // the attribute key before the SHARD_ guess: Sun Fish's attribute is "starborn", and the guess
        // SHARD_STARBORN is a different shard (the Starchild), so attributeLevel(p, "starborn") read the wrong one
        if (s == null) s = byAttr.get(any.toLowerCase(Locale.ROOT));
        if (s == null) s = get("SHARD_" + any.toUpperCase(Locale.ROOT));
        if (s == null) s = byCode.get(any.toUpperCase(Locale.ROOT));
        if (s == null) s = byName.get(key(any));
        if (s == null && any.toUpperCase(Locale.ROOT).startsWith("SHARD_")) s = byName.get(key(any.substring(6)));
        if (s == null && any.toLowerCase(Locale.ROOT).startsWith("critter_")) s = find(any.substring(8));
        return s;
    }

    /** Case, spaces, underscores, apostrophes and colour codes do not matter: "King Minos" = "king_minos" = "KINGMINOS". */
    public static String key(String name) {
        if (name == null) return "";
        final char section = (char) 0xA7;
        String t = name;
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c == '&' || c == section) && i + 1 < t.length()
                    && "0123456789abcdefklmnor".indexOf(Character.toLowerCase(t.charAt(i + 1))) >= 0) { i++; continue; }
            if (Character.isLetterOrDigit(c)) b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    /** Every name {@link #find} accepts besides ids (display and attribute names, normalized) - for tab completion. */
    public Collection<String> nameKeys() { return Collections.unmodifiableSet(byName.keySet()); }

    public ShardDef byAttr(String attr) { return attr == null ? null : byAttr.get(attr.toLowerCase(Locale.ROOT)); }

    public ShardDef byCritter(String mobId) { return mobId == null ? null : byCritter.get(mobId); }

    /** Shards a killed mob can yield (several shards may name the same mob). */
    public List<ShardDef> byMob(String mobId) { return mobId == null ? List.of() : byMob.getOrDefault(mobId, List.of()); }

    public Collection<ShardDef> all() { return Collections.unmodifiableCollection(byId.values()); }

    public int size() { return byId.size(); }

    /** The id line of a rarity, ordered by number (C1, C2, C3...). */
    public List<ShardDef> line(String rarity) { return lines.getOrDefault(rarity, List.of()); }

    public Collection<Habitat> habitats() { return Collections.unmodifiableCollection(habitats.values()); }

    public Habitat habitat(String id) { return habitats.get(id); }

    public Collection<String> mobIds() { return Collections.unmodifiableSet(byMob.keySet()); }
}
