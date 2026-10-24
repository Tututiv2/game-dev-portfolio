package com.skyclone.api.item;

import com.skyclone.api.stat.Stat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A reforge as DATA, registered by the reforging module through {@code Sky.items().registerReforge}.
 * Core uses it for the name prefix ("Spicy Diamond Sword"), the blue "(+X)" stat lines and the
 * player's stats. A special ability (the reforge stone "bonus" line) is behaviour the reforge
 * module implements itself.
 */
public class ReforgeDef {
    public String id;           // "spicy"
    public String name;         // "Spicy"
    public Set<ItemCategory> appliesTo = new HashSet<>();
    /** Stats per rarity. Missing rarity = nearest lower one defined. */
    public Map<Rarity, Map<Stat, Double>> stats = new EnumMap<>(Rarity.class);
    /** Null = a random blacksmith reforge; otherwise the reforge stone item id that applies it. */
    public String stoneItemId;
    /** Coin cost to apply, per rarity. */
    public Map<Rarity, Double> cost = new EnumMap<>(Rarity.class);
    public String bonusName;
    public List<String> bonusLore = new ArrayList<>();

    public Map<Stat, Double> statsFor(Rarity r) {
        for (int i = r.ordinal(); i >= 0; i--) {
            Map<Stat, Double> m = stats.get(Rarity.values()[i]);
            if (m != null) return m;
        }
        return Map.of();
    }
}
