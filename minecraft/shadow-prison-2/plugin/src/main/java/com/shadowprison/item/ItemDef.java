package com.shadowprison.item;

import com.shadowprison.stat.StatMap;
import org.bukkit.Material;

import java.util.List;

/**
 * Everything the game knows about one item type, declared once.
 *
 * @param id          stable id stored in PDC — NEVER change one after launch (it orphans every copy)
 * @param category    shown in the rarity footer: "LEGENDARY PICKAXE"
 * @param unique      true = every copy is a tracked instance with its own uid (serials, minted gear)
 * @param model       optional resource-pack item model key, e.g. "shadowprison:void_pick"
 */
public record ItemDef(String id, Material material, String name, Rarity rarity, String category,
                      StatMap stats, List<String> description, boolean unique, String model) {

    public ItemDef {
        if (id == null || !id.matches("[a-z0-9_]{1,48}")) {
            // SP1 golden rule 8: an id from free text with a comma took the whole plugin down.
            throw new IllegalArgumentException("item id must be [a-z0-9_]{1,48}: " + id);
        }
        stats = stats == null ? new StatMap() : stats;
        description = description == null ? List.of() : List.copyOf(description);
        category = category == null ? "ITEM" : category.toUpperCase(java.util.Locale.ROOT);
    }

    public static Builder builder(String id, Material material) {
        return new Builder(id, material);
    }

    public static final class Builder {
        private final String id;
        private final Material material;
        private String name;
        private Rarity rarity = Rarity.COMMON;
        private String category = "ITEM";
        private final StatMap stats = new StatMap();
        private final java.util.ArrayList<String> description = new java.util.ArrayList<>();
        private boolean unique;
        private String model;

        private Builder(String id, Material material) {
            this.id = id;
            this.material = material;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder rarity(Rarity rarity) {
            this.rarity = rarity;
            return this;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder stat(com.shadowprison.stat.Stat stat, long value) {
            stats.add(stat, value);
            return this;
        }

        public Builder line(String text) {
            description.add(text);
            return this;
        }

        public Builder unique() {
            this.unique = true;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public ItemDef build() {
            return new ItemDef(id, material, name == null ? id : name, rarity, category, stats, description, unique, model);
        }
    }
}
