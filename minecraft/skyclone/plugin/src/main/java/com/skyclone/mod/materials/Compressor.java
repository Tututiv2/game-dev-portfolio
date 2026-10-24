package com.skyclone.mod.materials;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns raw materials in a player's inventory into their compressed form through the SAME recipe
 * data the crafting table uses: the recipe must be unlocked, every input is counted and removed at
 * the moment of crafting (never trusting what a menu showed a tick ago), and the result goes through
 * {@code Sky.loot().give(.., CRAFT)} so collections never count it and sacks may absorb it.
 */
public final class Compressor {

    /** Outcome of one compress action, for menus, chat and RCON. */
    public record Result(boolean ok, int crafts, int produced, String message) {}

    private final MaterialsModule module;
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    Compressor(MaterialsModule module) { this.module = module; }

    static boolean known(String id) { return Sky.items().exists(id); }

    /** Crafting-table recipes that produce {@code id}, live registry, first one first. */
    public static List<Recipe> recipesFor(String id) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : Sky.recipes().producing(id)) if (r.type == Recipe.Type.CRAFTING) out.add(r);
        return out;
    }

    /** A recipe counts as a compression when every input is one item kind repeated, or the catalogue says so. */
    public boolean isCompression(Recipe r) {
        if (r == null || r.type != Recipe.Type.CRAFTING) return false;
        if (module.catalogue().previous(r.result) != null) return true;
        return ChainMath.ingredients(r, Compressor::known).size() == 1 && ChainMath.totalInputs(r, Compressor::known) >= 4;
    }

    /** How many of this id the player can use: inventory, plus sacks when the dial allows it. */
    public int available(Player p, String id) {
        int n = Sky.items().count(p, id);
        if (module.dialFromSacks().get() && Sky.has(Features.Sacks.class)) {
            n += Math.max(0, Sky.service(Features.Sacks.class).count(p, id));
        }
        return n;
    }

    public int craftable(Player p, Recipe r, int max) {
        return ChainMath.timesCraftable(r, Compressor::known, id -> available(p, id), max);
    }

    /**
     * Craft {@code r} up to {@code times} times from the player's own items. Safe against double
     * clicks (a short per-player cooldown) and against inventories that changed since the menu drew.
     */
    public Result compress(Player p, Recipe r, int times) {
        if (r == null) return new Result(false, 0, 0, "&cThere is no recipe for that.");
        if (!module.dialCompressEnabled().get()) return new Result(false, 0, 0, "&cCompressing from the catalogue is disabled.");
        long now = System.currentTimeMillis();
        Long last = lastUse.get(p.getUniqueId());
        if (last != null && now - last < module.dialCooldownMs().get()) return new Result(false, 0, 0, "&cSlow down!");
        lastUse.put(p.getUniqueId(), now);
        if (!Sky.items().exists(r.result)) return new Result(false, 0, 0, "&cThat item does not exist.");
        if (!Sky.recipes().unlocked(p, r)) {
            String why = r.unlock == null ? "" : Sky.requirements().describe(r.unlock);
            return new Result(false, 0, 0, "&cRecipe locked! &7Requires " + (why.isBlank() ? r.unlock : why) + "&7.");
        }
        int cap = Math.max(1, Math.min(times, module.dialMaxPerClick().get()));
        int n = craftable(p, r, cap);
        if (n <= 0) return new Result(false, 0, 0, "&cYou don't have enough materials!");

        Map<String, Integer> ing = ChainMath.ingredients(r, Compressor::known);
        Map<String, Integer> taken = new LinkedHashMap<>();
        boolean short_ = false;
        for (Map.Entry<String, Integer> e : ing.entrySet()) {
            int need = e.getValue() * n;
            int got = take(p, e.getKey(), need);
            taken.put(e.getKey(), got);
            if (got < need) { short_ = true; break; }
        }
        if (short_) {
            // give back exactly what was taken; nothing was crafted
            for (Map.Entry<String, Integer> e : taken.entrySet()) giveAll(p, e.getKey(), e.getValue(), LootSource.OTHER);
            return new Result(false, 0, 0, "&cYour inventory changed; nothing was compressed.");
        }
        int produced = n * Math.max(1, r.amount);
        giveAll(p, r.result, produced, LootSource.CRAFT);
        ItemDef d = Sky.items().def(r.result);
        String name = d == null ? r.result : d.rarity.code() + d.name;
        return new Result(true, n, produced, "&aCompressed &e" + Text.commasInt(produced) + "x " + name + "&a!");
    }

    /** Remove from inventory first, then sacks (when allowed). Returns how many were removed. */
    private int take(Player p, String id, int amount) {
        int got = Sky.items().remove(p, id, amount);
        if (got < amount && module.dialFromSacks().get() && Sky.has(Features.Sacks.class)) {
            got += Math.max(0, Sky.service(Features.Sacks.class).remove(p, id, amount - got));
        }
        return got;
    }

    /** loot.give creates one stack per call (clamped to the max stack), so hand out in stack-sized chunks. */
    static void giveAll(Player p, String id, int amount, LootSource source) {
        if (amount <= 0 || !Sky.items().exists(id)) return;
        ItemDef d = Sky.items().def(id);
        int stack = Math.max(1, d == null ? 64 : d.effectiveMaxStack());
        int left = amount;
        while (left > 0) {
            int n = Math.min(stack, left);
            Sky.loot().give(p, id, n, source);
            left -= n;
        }
    }
}
