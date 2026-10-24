package com.skyclone.mod.collections;

import com.skyclone.api.Sky;
import com.skyclone.api.item.EnchantDef;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a tier's rewards into lore/chat lines. Item names always come from the item registry, so the
 * owning module's rename is what players read; an item nobody registered is left out (unless the
 * {@code collections.show-unregistered-rewards} dial is on) rather than showing a reference-game name.
 *
 * <p>Also indexes {@code Sky.recipes()} by unlock key, so a recipe another module gated behind
 * "collection:WHEAT:8" is listed under Wheat VIII even if the research data never mentioned it.
 */
final class CollectionRewards {

    private static final Pattern ROMAN_TAIL = Pattern.compile("\\s+[IVXLC]+$");
    private static final Pattern GENERATOR = Pattern.compile("^(.+_GENERATOR)_\\d+$");

    private final CollectionsModule mod;
    /** "WHEAT:8" -> result ids of recipes unlocked there. */
    private Map<String, Set<String>> recipeIndex = Map.of();
    private int indexedRecipes = -1;

    CollectionRewards(CollectionsModule mod) { this.mod = mod; }

    // ---- recipe index ---------------------------------------------------------------------------

    /** Rebuilt whenever the recipe registry changed size (modules register in enable/postEnable). */
    Map<String, Set<String>> recipeIndex() {
        int n;
        try { n = Sky.recipes().all().size(); } catch (RuntimeException e) { return recipeIndex; }
        if (n == indexedRecipes) return recipeIndex;
        Map<String, Set<String>> idx = new HashMap<>();
        for (Recipe r : Sky.recipes().all()) {
            if (r == null || r.unlock == null || r.result == null) continue;
            for (String part : r.unlock.split("&")) {
                String key = part.trim();
                if (!key.startsWith("collection:")) continue;
                CollectionKey ck = CollectionKey.parse(mod.catalog(), key);
                if (ck == null || ck.tier() <= 0) continue;
                idx.computeIfAbsent(ck.coll().id + ":" + ck.tier(), k -> new LinkedHashSet<>()).add(r.result);
            }
        }
        recipeIndex = idx;
        indexedRecipes = n;
        return idx;
    }

    /** Result ids of registered recipes gated on this exact tier. */
    Set<String> registeredUnlocks(CollectionCatalog.Coll c, int tier) {
        return recipeIndex().getOrDefault(c.id + ":" + tier, Set.of());
    }

    // ---- names -------------------------------------------------------------------------------

    /** Registry display name (colour codes stripped) or null when the id is not registered. */
    static String itemName(String id) {
        if (id == null) return null;
        try {
            ItemDef d = Sky.items().def(id);
            if (d == null || d.name == null || d.name.isBlank()) return null;
            return Text.strip(d.name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String enchantName(String id, String fallback) {
        try {
            EnchantDef d = Sky.items().enchant(id);
            if (d != null && d.name != null && !d.name.isBlank()) return Text.strip(d.name);
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    /** The collection's display name: the registry's name for its item when registered, else ours. */
    String collName(CollectionCatalog.Coll c) {
        if (!c.isBoss()) {
            String reg = itemName(c.id);
            if (reg != null) return reg;
        }
        return c.name;
    }

    // ---- lines -------------------------------------------------------------------------------

    /**
     * Reward lines for one tier (no indent). {@code max} caps the list (0 = no cap); the SkyBlock XP
     * line is always last and always present.
     */
    List<String> lines(CollectionCatalog.Coll c, CollectionCatalog.Tier t, int max) {
        List<String> out = new ArrayList<>();
        Set<String> shown = new LinkedHashSet<>();
        Set<String> minionPrefixes = new LinkedHashSet<>();
        boolean showUnreg = mod.showUnregistered();
        for (CollectionCatalog.Reward r : t.rewards) {
            String line = line(r, shown, minionPrefixes, showUnreg);
            if (line != null) out.add(line);
        }
        // recipes other modules gated here that the research data did not list
        for (String res : registeredUnlocks(c, t.tier)) {
            if (shown.contains(res)) continue;
            var m = GENERATOR.matcher(res);
            if (m.matches()) {
                if (minionPrefixes.add(m.group(1))) out.add("&a" + minionName(m.group(1), null) + " &7Recipes");
                continue;
            }
            String n = itemName(res);
            if (n != null && shown.add(res)) out.add("&a" + n + " &7Recipe");
        }
        if (max > 0 && out.size() > max) {
            int more = out.size() - (max - 1);
            out = new ArrayList<>(out.subList(0, max - 1));
            out.add("&8...and " + more + " more");
        }
        int sb = (int) Math.round(t.sbxp * mod.sbxpMultiplier());
        if (sb > 0) out.add("&8+&b" + sb + " SkyBlock XP");
        return out;
    }

    private String line(CollectionCatalog.Reward r, Set<String> shown, Set<String> minionPrefixes, boolean showUnreg) {
        if (r == null || r.type == null) return null;
        switch (r.type) {
            case "recipe" -> {
                String n = itemName(r.item);
                if (n == null) {
                    if (!showUnreg || r.label == null) return null;
                    n = r.label;
                }
                if (r.item != null) shown.add(r.item);
                return "&a" + n + " &7" + (r.forge ? "Forge Recipe" : "Recipe");
            }
            case "minion" -> {
                if (r.prefix != null) minionPrefixes.add(r.prefix);
                return "&a" + minionName(r.prefix, r.label) + " &7Recipes";
            }
            case "skillxp" -> {
                SkillType s = SkillType.parse(r.skill);
                return "&8+&3" + Text.commasInt(r.amount) + " &7" + (s != null ? s.display : Text.titleCase(r.skill)) + " Experience";
            }
            case "skillcap" -> {
                SkillType s = SkillType.parse(r.skill);
                return "&8+&a" + r.amount + " &7" + (s != null ? s.display : Text.titleCase(r.skill)) + " Level Cap";
            }
            case "discount" -> {
                return "&a" + enchantName(r.enchant, r.label) + " &7Exp Discount (&a-" + r.percent + "%&7)";
            }
            case "stat" -> {
                Stat s = Stat.parse(r.stat);
                return s == null ? "&8+&a" + r.amount + " " + Text.titleCase(r.stat) : "&8+" + "&" + s.color + r.amount + " " + s.label();
            }
            case "pet" -> {
                return "&a" + r.label + " Pet &7Recipe";
            }
            case "book" -> {
                return "&aEnchanted Book (" + enchantName(r.enchant, r.label) + " " + r.level + ") &7Recipe";
            }
            case "potion" -> {
                return "&a" + r.label + " &7Recipe";
            }
            case "loot" -> {
                String n = itemName(r.item);
                if (n == null) n = r.label;
                if (n == null) return null;
                return "&a" + n + " &7(reward chest unlock)";
            }
            default -> {
                return r.label == null ? null : "&a" + r.label;
            }
        }
    }

    /** "Wheat Minion" from the registry's tier-I minion name, else the data label. */
    static String minionName(String prefix, String fallback) {
        String n = prefix == null ? null : itemName(prefix + "_1");
        if (n != null) return ROMAN_TAIL.matcher(n).replaceAll("");
        return fallback != null ? fallback : (prefix == null ? "Minion" : Text.titleCase(prefix.replace("_GENERATOR", "")) + " Minion");
    }
}
