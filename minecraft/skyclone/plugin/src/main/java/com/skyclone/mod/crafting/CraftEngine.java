package com.skyclone.mod.crafting;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Doing a craft, safely. Every path follows the same order: re-read the materials NOW (handlers run
 * a tick after the click), verify, remove, then give the result through {@code Sky.loot().give}
 * with source CRAFT. Nothing is given before its inputs are gone.
 */
final class CraftEngine {

    final RecipeIndex idx;
    final CraftDials dials;
    /** Admins testing: recipe unlocks are ignored for these players (in memory, cleared on restart). */
    final Set<UUID> bypass = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastCraft = new ConcurrentHashMap<>();

    CraftEngine(RecipeIndex idx, CraftDials dials) {
        this.idx = idx;
        this.dials = dials;
    }

    CraftData data(Player p) {
        if (p == null || !Sky.profiles().isReady(p)) return null;
        return Sky.profiles().member(p, "crafting", CraftData.class);
    }

    boolean unlocked(Player p, RecipeIndex.Entry e) {
        if (e == null) return false;
        if (p == null) return e.recipe.unlock == null;
        if (bypass.contains(p.getUniqueId())) return true;
        try { return Sky.recipes().unlocked(p, e.recipe); } catch (RuntimeException ex) { return false; }
    }

    String lockText(RecipeIndex.Entry e) {
        if (e.recipe.unlock == null) return "";
        return describe(e.recipe.unlock);
    }

    /**
     * Requirement text for an unlock key. Wave 5: the 3 Mutations sacks are gated on {@code garden:analyzer:N}
     * (Crop Analyzer Milestone N). Garden's resolver owns the {@code garden} prefix but does not parse that sub-key
     * yet, so it fails closed (locked) and would describe it as "Garden Level analyzer" - we name it ourselves
     * until garden does (see the hand-off, Requests).
     */
    static String describe(String unlock) {
        if (unlock == null || unlock.isBlank()) return "";
        String raw;
        try { raw = Sky.requirements().describe(unlock); } catch (RuntimeException ex) { raw = unlock; }
        if (!unlock.contains("garden:analyzer:") || raw == null || !raw.contains("Garden Level")) return raw;
        List<String> out = new ArrayList<>();
        for (String part : unlock.split("&")) {
            String k = part.trim();
            String fixed = CraftMath.analyzerText(k);
            if (fixed != null) { out.add(fixed); continue; }
            try { out.add(Sky.requirements().describe(k)); } catch (RuntimeException ex) { out.add(k); }
        }
        return String.join(" &7and ", out);
    }

    private boolean rateOk(Player p) {
        long now = System.currentTimeMillis();
        Long prev = lastCraft.get(p.getUniqueId());
        if (prev != null && now - prev < Math.max(0, dials.craftRateMs.get())) return false;
        lastCraft.put(p.getUniqueId(), now);
        return true;
    }

    void forget(UUID u) { lastCraft.remove(u); bypass.remove(u); }

    // ---- value -------------------------------------------------------------------------------------

    /** Coins one unit is worth: the better of NPC sell and bazaar insta-sell. */
    static double value(String id) {
        double v = 0;
        ItemDef d = Sky.items().def(id);
        if (d != null) v = d.npcSell;
        if (Sky.has(Features.Bazaar.class)) {
            try {
                Features.Bazaar bz = Sky.service(Features.Bazaar.class);
                if (bz.isBazaarItem(id)) v = Math.max(v, bz.instantSell(id));
            } catch (RuntimeException ignored) {}
        }
        return v;
    }

    static double npcSell(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? 0 : d.npcSell;
    }

    static String name(String id) {
        ItemDef d = Sky.items().def(id);
        if (d == null) return "&c" + id;
        return d.rarity.code() + d.name;
    }

    // ---- the grid ------------------------------------------------------------------------------------

    record Grid(String[] ids, int[] amts, ItemStack[] stacks) {
        boolean empty() { for (String s : ids) if (s != null) return false; return true; }
    }

    record Found(Matcher.Match match, RecipeIndex.Entry entry, boolean unlocked, boolean enough) {}

    Grid read(Inventory inv, int[] slots) {
        String[] ids = new String[9];
        int[] amts = new int[9];
        ItemStack[] stacks = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(slots[i]);
            if (s == null || s.getType().isAir()) continue;
            stacks[i] = s;
            ids[i] = idx.stackId(s);
            amts[i] = s.getAmount();
        }
        return new Grid(ids, amts, stacks);
    }

    /**
     * The recipe the grid shows. Preference: an unlocked match, then a locked match, then a recipe
     * whose SHAPE matches but whose slots hold too few items ({@code enough} false).
     */
    Found find(Player p, Grid g) {
        if (g.empty()) return null;
        String first = null;
        for (String s : g.ids()) if (s != null) { first = s; break; }
        List<RecipeIndex.Entry> cands = idx.using(first);
        boolean mirror = dials.allowMirror.get();
        Found locked = null, shapeOnly = null;
        int[] big = new int[9];
        for (int i = 0; i < 9; i++) big[i] = g.ids()[i] == null ? 0 : 1_000_000;
        for (RecipeIndex.Entry e : cands) {
            Matcher.Match m = Matcher.match(e.pattern, g.ids(), g.amts(), mirror);
            if (m != null) {
                boolean ok = unlocked(p, e);
                if (ok) return new Found(m, e, true, true);
                if (locked == null) locked = new Found(m, e, false, true);
                continue;
            }
            if (shapeOnly == null) {
                Matcher.Match s = Matcher.match(e.pattern, g.ids(), big, mirror);
                if (s != null) shapeOnly = new Found(s, e, unlocked(p, e), false);
            }
        }
        return locked != null ? locked : shapeOnly;
    }

    /** Craft from the menu grid. Returns the number of crafts made. */
    int craftGrid(Player p, Inventory inv, int[] slots, boolean max) {
        if (!rateOk(p)) return 0;
        Grid g = read(inv, slots);
        Found f = find(p, g);
        if (f == null) { Msg.error(p, "That is not a valid recipe!"); return 0; }
        if (!f.enough()) { Msg.error(p, "Some slots do not hold enough items for this recipe!"); return 0; }
        if (!f.unlocked()) { Msg.error(p, "You have not unlocked this recipe! &7Requires " + lockText(f.entry()) + "&7."); return 0; }
        Pattern pat = f.entry().pattern;
        ItemDef rd = Sky.items().def(pat.result);
        if (rd == null) { Msg.error(p, "This recipe makes an item that does not exist yet."); return 0; }
        int times = max ? Math.min(f.match().maxTimes(), Math.max(1, dials.shiftCraftCap.get())) : 1;

        ItemData carry = null;
        if (dials.carryUpgrades.get() && rd.isUnique()) {
            for (int i = 0; i < 9; i++) {
                if (f.match().consume()[i] <= 0 || g.stacks()[i] == null) continue;
                ItemData d = carryFrom(g.stacks()[i], rd);
                if (d != null) { carry = d; times = 1; break; }
            }
        }
        times = limitBySpace(p, pat, times);
        if (times <= 0) { Msg.error(p, "You don't have enough inventory space for that!"); return 0; }
        times = limitByCoins(p, pat, times);
        if (times <= 0) { Msg.error(p, "You need " + Text.commas(pat.coins) + " coins to craft this!"); return 0; }
        if (pat.coins > 0 && !Sky.economy().withdraw(p, pat.coins * times, "crafting: " + pat.result + " x" + times)) {
            Msg.error(p, "You don't have enough coins!");
            return 0;
        }
        // consume from the live grid
        Map<String, Integer> used = new HashMap<>();
        for (int i = 0; i < 9; i++) {
            int take = f.match().consume()[i] * times;
            if (take <= 0) continue;
            ItemStack s = inv.getItem(slots[i]);
            if (s == null) continue;
            if (g.ids()[i] != null) used.merge(g.ids()[i], Math.min(take, s.getAmount()), Integer::sum);
            if (s.getAmount() <= take) inv.setItem(slots[i], null);
            else { s.setAmount(s.getAmount() - take); inv.setItem(slots[i], s); }
        }
        give(p, pat, times, carry);
        giveRemainders(p, used);
        after(p, f.entry(), times, false);
        return times;
    }

    /** Supercraft / Quick Craft / admin: craft straight from inventory (+ sacks). Returns crafts made. */
    int craftStock(Player p, RecipeIndex.Entry e, int times, boolean tell) {
        if (e == null || times <= 0) return 0;
        if (!rateOk(p)) return 0;
        if (!unlocked(p, e)) {
            if (tell) Msg.error(p, "You have not unlocked this recipe! &7Requires " + lockText(e) + "&7.");
            return 0;
        }
        Pattern pat = e.pattern;
        ItemDef rd = Sky.items().def(pat.result);
        if (rd == null) { if (tell) Msg.error(p, "This recipe makes an item that does not exist yet."); return 0; }
        boolean sacks = dials.useSacks.get();
        Map<String, Integer> stock = Stock.withSacks(p, Stock.inventory(p, idx), Matcher.allIds(pat), sacks, null);
        boolean upgrade = dials.carryUpgrades.get() && rd.isUnique() && hasSameCategoryUnique(pat, rd);
        int cap = Math.max(1, dials.superCraftCap.get());
        if (upgrade) cap = 1;
        int can = Matcher.maxTimes(pat, stock, Math.min(times, cap));
        if (can <= 0) {
            if (tell) Msg.error(p, "You don't have the materials for that!");
            return 0;
        }
        int n = limitBySpace(p, pat, can);
        if (n <= 0) { if (tell) Msg.error(p, "You don't have enough inventory space for that!"); return 0; }
        n = limitByCoins(p, pat, n);
        if (n <= 0) { if (tell) Msg.error(p, "You need " + Text.commas(pat.coins) + " coins to craft this!"); return 0; }
        Map<String, Integer> plan = Matcher.plan(pat, stock, n);
        if (plan == null) { if (tell) Msg.error(p, "You don't have the materials for that!"); return 0; }
        // Which copy of a unique ingredient goes: the most upgraded when its upgrades carry over, else the plainest.
        Stock.Taken taken = Stock.take(p, idx, plan, sacks, upgrade ? 1 : -1);
        if (taken == null) { if (tell) Msg.error(p, "Your materials changed, try again!"); return 0; }
        if (pat.coins > 0 && !Sky.economy().withdraw(p, pat.coins * n, "crafting: " + pat.result + " x" + n)) {
            Stock.refund(p, taken);
            if (tell) Msg.error(p, "You don't have enough coins!");
            return 0;
        }
        ItemData carry = null;
        if (upgrade) {
            int best = -1;
            for (ItemStack s : taken.fromInventory) {
                ItemData c = carryFrom(s, rd);
                if (c != null && CraftMath.upgradeWorth(c) > best) { carry = c; best = CraftMath.upgradeWorth(c); }
            }
        }
        give(p, pat, n, carry);
        giveRemainders(p, plan);
        after(p, e, n, true);
        if (tell) Msg.send(p, "&aCrafted &f" + Text.commas((double) pat.amount * n) + "x " + name(pat.result) + "&a!");
        return n;
    }

    private boolean hasSameCategoryUnique(Pattern pat, ItemDef rd) {
        for (Pattern.Ing i : pat.cells) {
            if (i == null) continue;
            for (String id : i.ids()) {
                ItemDef d = Sky.items().def(id);
                if (d != null && d.isUnique() && d.category == rd.category) return true;
            }
        }
        return false;
    }

    /** The instance data worth keeping when this stack is upgraded into {@code result}, or null. */
    private ItemData carryFrom(ItemStack s, ItemDef result) {
        String id = Sky.items().idOf(s);
        ItemDef d = id == null ? null : Sky.items().def(id);
        if (d == null || !d.isUnique() || d.category != result.category) return null;
        ItemData data = Sky.items().data(s);
        boolean worth = !data.enchants.isEmpty() || data.reforge != null || data.potatoBooks > 0 || data.recombobulated
                || data.stars > 0 || !data.gems.isEmpty() || !data.unlockedGemSlots.isEmpty() || data.extra.size() > 0;
        return worth ? data : null;
    }

    private int limitBySpace(Player p, Pattern pat, int times) {
        int cap = Stock.capacity(p, pat.result);
        return Math.min(times, cap / Math.max(1, pat.amount));
    }

    private int limitByCoins(Player p, Pattern pat, int times) {
        if (pat.coins <= 0) return times;
        double purse = Sky.economy().purse(p);
        return (int) Math.min(times, Math.floor(purse / pat.coins));
    }

    private void give(Player p, Pattern pat, int times, ItemData carry) {
        ItemDef d = Sky.items().def(pat.result);
        long total = (long) pat.amount * times;
        if (d.isUnique()) {
            for (long k = 0; k < total; k++) {
                ItemStack it = Sky.items().create(pat.result, 1);
                if (carry != null && k == 0) {
                    final ItemData c = carry;
                    Sky.items().update(it, nd -> {
                        nd.enchants.putAll(c.enchants);
                        if (c.reforge != null) nd.reforge = c.reforge;
                        nd.potatoBooks = Math.max(nd.potatoBooks, c.potatoBooks);
                        nd.recombobulated |= c.recombobulated;
                        nd.stars = Math.max(nd.stars, c.stars);
                        nd.gems.putAll(c.gems);
                        nd.unlockedGemSlots.addAll(c.unlockedGemSlots);
                        for (Map.Entry<String, com.google.gson.JsonElement> x : c.extra.entrySet()) {
                            if (!nd.extra.has(x.getKey())) nd.extra.add(x.getKey(), x.getValue().deepCopy());
                        }
                    });
                }
                Sky.loot().give(p, it, LootSource.CRAFT);
            }
            return;
        }
        int max = Math.max(1, d.effectiveMaxStack());
        while (total > 0) {
            int n = (int) Math.min(max, total);
            Sky.loot().give(p, Sky.items().create(pat.result, n), LootSource.CRAFT);
            total -= n;
        }
    }

    /**
     * Vanilla behaviour: a plain Milk/Water/Lava Bucket or Honey Bottle used in a recipe leaves its empty container
     * behind (the cake hands its buckets back). Only the PLAIN vanilla item counts: an Enchanted Lava Bucket is
     * made of a lava bucket but is not one.
     */
    @SuppressWarnings("deprecation")
    private void giveRemainders(Player p, Map<String, Integer> used) {
        for (Map.Entry<String, Integer> en : used.entrySet()) {
            if (en.getValue() <= 0) continue;
            ItemDef d = Sky.items().def(en.getKey());
            if (d == null || d.material == null || d.isUnique()) continue;
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(d.material);
            if (m == null || !en.getKey().equals(Sky.items().vanillaId(m))) continue;
            org.bukkit.Material rem;
            try { rem = m.getCraftingRemainingItem(); } catch (RuntimeException ex) { rem = null; }
            if (rem == null || rem.isAir()) continue;
            String rid = Sky.items().vanillaId(rem);
            if (rid == null || !Sky.items().exists(rid)) continue;
            int left = en.getValue();
            int max = Math.max(1, Sky.items().def(rid).effectiveMaxStack());
            while (left > 0) {
                int k = Math.min(max, left);
                Sky.loot().give(p, Sky.items().create(rid, k), LootSource.CRAFT);
                left -= k;
            }
        }
    }

    /** Bookkeeping after any craft: stats, recent list, Carpentry XP, sound. */
    private void after(Player p, RecipeIndex.Entry e, int times, boolean stock) {
        Pattern pat = e.pattern;
        CraftData d = data(p);
        if (d != null) {
            d.crafted += (long) pat.amount * times;
            d.craftedBy.merge(pat.result, (long) pat.amount * times, Long::sum);
            d.recent.remove(pat.result);
            d.recent.add(0, pat.result);
            int keep = Math.max(1, dials.recentSize.get());
            while (d.recent.size() > keep) d.recent.remove(d.recent.size() - 1);
        }
        double xp = carpentryXp(pat) * times;
        if (xp > 0) {
            Sky.service(SkillService.class).addXp(p, SkillType.CARPENTRY, xp, "crafting:" + pat.result);
            if (d != null) d.carpentryXp += xp;
        }
        if (dials.craftSounds.get()) Msg.sound(p, stock ? Sound.BLOCK_ANVIL_USE : Sound.ENTITY_ITEM_PICKUP, stock ? 1.6f : 1.2f);
    }

    /**
     * Carpentry XP one craft of this recipe grants: 3% (dial) of the ingredients' NPC value (research/02 §2.4.8),
     * nothing for vanilla recipes (unless the dial says so), and nothing for a recipe another recipe undoes,
     * since crafting a block and back forever would otherwise be endless skill XP and skill coin rewards.
     */
    double carpentryXp(Pattern pat) {
        if (pat.vanilla && !dials.vanillaCarpentryXp.get()) return 0;
        if (idx.reversible(pat.recipeId)) return 0;
        return rawCarpentryXp(pat);
    }

    /** Why a recipe pays no Carpentry XP, or null when it does. */
    String noXpReason(Pattern pat) {
        if (pat.vanilla && !dials.vanillaCarpentryXp.get()) return "Vanilla recipes give no Carpentry XP.";
        if (idx.reversible(pat.recipeId)) return "Can be crafted back: no Carpentry XP.";
        return null;
    }

    double rawCarpentryXp(Pattern pat) {
        Map<String, Integer> per = new LinkedHashMap<>();
        pat.totals().forEach((ing, n) -> per.merge(ing.display(), n, Integer::sum));
        return CraftMath.carpentryXp(per, CraftEngine::npcSell, dials.carpentryRate.get());
    }

    // ---- planning helpers for menus -------------------------------------------------------------------

    /** Stock (inventory + sacks when on) restricted to what this pattern can use. */
    Map<String, Integer> stockFor(Player p, Pattern pat, Map<String, Integer> inv, Map<String, Integer> sackMemo) {
        if (p == null) return new HashMap<>();
        return Stock.withSacks(p, inv, Matcher.allIds(pat), dials.useSacks.get(), sackMemo);
    }

    /** Lore lines "✔ 32x Diamond (have 40)" for each ingredient of a pattern. */
    List<String> checklist(Pattern pat, Map<String, Integer> stock, int times) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Pattern.Ing, Integer> en : pat.totals().entrySet()) {
            long need = (long) en.getValue() * Math.max(1, times);
            long have = 0;
            for (String id : en.getKey().ids()) have += stock.getOrDefault(id, 0);
            boolean ok = have >= need;
            String nm = name(en.getKey().display()) + (en.getKey().ids().size() > 1 ? " &8(any)" : "");
            out.add((ok ? "&a✔ " : "&c✖ ") + "&7" + Text.commas(need) + "x " + nm + " &8(" + (ok ? "&a" : "&c") + Text.shortNum(have) + "&8)");
        }
        if (pat.coins > 0) out.add("&6" + Text.commas(pat.coins * Math.max(1, times)) + " Coins");
        return out;
    }

    /** Count of a single id the player has (inventory + sacks). */
    int have(Player p, String id) {
        if (p == null) return 0;
        Map<String, Integer> inv = Stock.inventory(p, idx);
        return Stock.withSacks(p, inv, Set.of(id), dials.useSacks.get(), null).getOrDefault(id, 0);
    }

    static JsonObject copy(JsonObject o) { return o == null ? new JsonObject() : o.deepCopy(); }
}
