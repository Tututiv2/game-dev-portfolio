package com.skyclone.mod.crafting;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The genre's 6-row "Craft Item" menu: a free 3x3 grid on the left (10-12, 19-21, 28-30), the
 * result at 23, Quick Crafting down the right (16, 25, 34), and a status strip along the bottom
 * that turns green when the grid holds a craftable recipe. Core hands every grid item back on close.
 */
final class CraftMenu {

    static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    static final int RESULT = 23;
    static final int[] QUICK = {16, 25, 34};
    static final int[] STRIP = {45, 46, 47, 51, 52, 53};
    static final int RETURN = 38;
    static final int BOOK = 50;

    private final CraftingModule mod;

    CraftMenu(CraftingModule mod) { this.mod = mod; }

    void open(Player p) {
        if (p == null || !Sky.profiles().isReady(p)) return;
        build(p).open(p);
    }

    /**
     * The menu, not yet opened. The grid slots are FREE and the menu does NOT keep them: core hands every
     * grid item back to the player on close, quit or when another menu replaces this one (self-test
     * {@code crafting.grid-menu} asserts both).
     */
    Menu build(Player p) {
        Menu m = new Menu("Craft Item", 6);
        m.free(GRID);
        m.chrome();
        final QuickState qs = new QuickState();
        m.drawer(menu -> draw(p, menu, qs));
        m.onFreeSlotChange(c -> c.menu().redraw());
        m.refreshEvery(20);
        return m;
    }

    /**
     * Quick Crafting suggestions are a scan over every recipe, so they are recomputed only when the
     * player's inventory changed, after a craft, or every 5 s (sacks can change without the inventory).
     */
    private static final class QuickState {
        final List<RecipeIndex.Entry> list = new ArrayList<>();
        long at;
        Map<String, Integer> inv;
        String hintKey;
        final List<RecipeIndex.Entry> hints = new ArrayList<>();
        final List<Integer> hintMissing = new ArrayList<>();
        void invalidate() { at = 0; inv = null; }
    }

    private void draw(Player p, Menu m, QuickState qs) {
        CraftEngine eng = mod.engine;
        Inventory inv = m.getInventory();
        CraftEngine.Grid g = eng.read(inv, GRID);
        CraftEngine.Found f = eng.find(p, g);

        Material strip;
        if (f == null) {
            strip = Material.RED_STAINED_GLASS_PANE;
            if (g.empty()) {
                m.set(RESULT, Icons.of(Material.BARRIER, "&cRecipe Required",
                        List.of("&7Put items in the grid on the left", "&7to craft something!", "",
                                "&8Not sure what to make? The", "&8Recipe Book lists everything.")));
            } else {
                hints(p, g, qs);
                List<String> lore = new ArrayList<>(List.of("&7This arrangement doesn't make", "&7anything. Check the &aRecipe Book&7!"));
                if (!qs.hints.isEmpty()) {
                    lore.add("");
                    lore.add("&7Recipes that use these items:");
                    for (int i = 0; i < qs.hints.size(); i++) {
                        RecipeIndex.Entry h = qs.hints.get(i);
                        int miss = qs.hintMissing.get(i);
                        lore.add(" &8- " + CraftEngine.name(h.pattern.result) + (miss == 0 ? " &8(check shape/amounts)" : " &8(+" + miss + " more item" + (miss == 1 ? "" : "s") + ")"));
                    }
                    lore.add("");
                    lore.add("&eClick to view " + Text.strip(displayName(qs.hints.get(0).pattern.result)) + "'s recipe!");
                    lore.add("&8Grid items go back to your inventory.");
                }
                RecipeIndex.Entry top = qs.hints.isEmpty() ? null : qs.hints.get(0);
                m.set(RESULT, Icons.of(Material.BARRIER, "&cRecipe Required", lore), top == null ? null : c -> {
                    int at = Math.max(0, mod.index.producing(top.pattern.result).indexOf(top));
                    mod.book.view(c.player(), top.pattern.result, at, () -> build(c.player())).open(c.player());
                });
            }
        } else {
            RecipeIndex.Entry e = f.entry();
            Pattern pat = e.pattern;
            List<String> lore = new ArrayList<>();
            if (!f.enough()) {
                strip = Material.ORANGE_STAINED_GLASS_PANE;
                lore.add("");
                lore.add("&cNot enough items!");
                lore.add("&7Each slot needs:");
                for (Map.Entry<Pattern.Ing, Integer> en : perSlot(pat).entrySet()) {
                    lore.add(" &8- &7" + en.getValue() + "x " + CraftEngine.name(en.getKey().display()));
                }
                m.set(RESULT, Icons.withLore(resultIcon(pat), lore));
            } else if (!f.unlocked()) {
                strip = Material.RED_STAINED_GLASS_PANE;
                lore.add("");
                lore.add("&cRecipe Locked!");
                lore.add("&7Requires " + eng.lockText(e) + "&7.");
                m.set(RESULT, Icons.withLore(resultIcon(pat), lore));
            } else {
                strip = Material.LIME_STAINED_GLASS_PANE;
                int maxTimes = Math.min(f.match().maxTimes(), Math.max(1, mod.dials.shiftCraftCap.get()));
                lore.add("");
                if (maxTimes > 1) lore.add("&7Grid holds enough for &a" + maxTimes + " &7crafts.");
                double xp = eng.carpentryXp(pat);
                if (xp > 0) lore.add("&8+&3" + Text.commas(Math.round(xp * 10) / 10.0) + " Carpentry XP");
                lore.add("");
                lore.add("&eClick to craft!");
                if (maxTimes > 1) lore.add("&eShift-click to craft max!");
                m.set(RESULT, Icons.withLore(resultIcon(pat), lore), c -> {
                    eng.craftGrid(c.player(), c.menu().getInventory(), GRID, c.shift());
                    qs.invalidate();
                    c.menu().redraw();
                });
            }
        }
        for (int s : STRIP) m.set(s, Icons.of(strip, " "));

        // Quick Crafting column
        boolean qcOpen = p != null && quickCraftUnlocked(p);
        long now = System.currentTimeMillis();
        Map<String, Integer> invNow = qcOpen ? Stock.inventory(p, mod.index) : null;
        if (qcOpen && (now - qs.at > 5000 || !invNow.equals(qs.inv))) {
            qs.list.clear();
            qs.list.addAll(quickCraft(p, Math.max(1, Math.min(3, mod.dials.quickCraftSlots.get()))));
            qs.at = now;
            qs.inv = invNow;
        }
        List<RecipeIndex.Entry> quick = qs.list;
        for (int i = 0; i < QUICK.length; i++) {
            int slot = QUICK[i];
            if (!qcOpen) {
                m.set(slot, Icons.of(Material.GRAY_DYE, "&cQuick Crafting Slot",
                        "&7Shows what you can craft right", "&7now from your inventory" + (mod.dials.useSacks.get() ? " and sacks" : "") + ".",
                        "", "&cUnlocks at &aCarpentry Skill " + Text.roman(Math.max(1, mod.dials.quickCraftLevel.get())) + "&c."));
                continue;
            }
            if (i >= quick.size() || i >= mod.dials.quickCraftSlots.get()) {
                m.set(slot, Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Quick Crafting Slot",
                        "&7Nothing else you can craft from", "&7your materials right now."));
                continue;
            }
            RecipeIndex.Entry e = quick.get(i);
            Map<String, Integer> stock = eng.stockFor(p, e.pattern, invNow, null);
            int can = Matcher.maxTimes(e.pattern, stock, Math.max(1, mod.dials.superCraftCap.get()));
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("&8Quick Craft");
            lore.addAll(eng.checklist(e.pattern, stock, 1));
            lore.add("");
            if (can > 1) lore.add("&7You can craft this &a" + can + " &7times.");
            lore.add("&eClick to craft!");
            if (can > 1) lore.add("&eShift-click to craft max!");
            m.set(slot, Icons.withLore(resultIcon(e.pattern), lore), c -> {
                eng.craftStock(c.player(), e, c.shift() ? Integer.MAX_VALUE : 1, true);
                qs.invalidate();
                c.menu().redraw();
            });
        }

        m.set(RETURN, Icons.of(Material.HOPPER, "&aReturn Items", "&7Moves everything in the grid", "&7back into your inventory.", "", "&eClick to empty the grid!"),
                c -> { returnGrid(c.player(), c.menu()); c.menu().redraw(); });
        m.set(BOOK, Icons.of(Material.BOOK, "&aRecipe Book", "&7Browse every recipe, see what", "&7you can make and supercraft", "&7straight from your inventory.", "", "&eClick to open!"),
                c -> mod.book.openRoot(c.player()));
        if (Sky.has(Features.SkyMenu.class)) {
            m.set(m.closeSlot() - 1, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        }
    }

    /**
     * Wave 3 "did you mean": up to 3 recipes that use every item in a grid that makes nothing (unlocked first).
     * Recomputed only when the set of ids in the grid changes.
     */
    private void hints(Player p, CraftEngine.Grid g, QuickState qs) {
        java.util.TreeSet<String> ids = new java.util.TreeSet<>();
        for (String id : g.ids()) if (id != null) ids.add(id);
        String key = String.join(",", ids);
        if (key.equals(qs.hintKey)) return;
        qs.hintKey = key;
        qs.hints.clear();
        qs.hintMissing.clear();
        if (ids.isEmpty()) return;
        List<RecipeIndex.Entry> pool = null;
        for (String id : ids) {
            List<RecipeIndex.Entry> u = mod.index.using(id);
            if (pool == null || u.size() < pool.size()) pool = u;
        }
        if (pool == null || pool.isEmpty()) return;
        Map<Pattern, RecipeIndex.Entry> back = new java.util.IdentityHashMap<>();
        List<Pattern> pats = new ArrayList<>();
        for (RecipeIndex.Entry e : pool) { back.put(e.pattern, e); pats.add(e.pattern); }
        List<CraftMath.NearMiss> near = CraftMath.nearMisses(ids, pats, 12);
        List<CraftMath.NearMiss> open = new ArrayList<>(), shut = new ArrayList<>();
        for (CraftMath.NearMiss n : near) (mod.engine.unlocked(p, back.get(n.pattern())) ? open : shut).add(n);
        open.addAll(shut);
        for (int i = 0; i < open.size() && i < 3; i++) {
            qs.hints.add(back.get(open.get(i).pattern()));
            qs.hintMissing.add(open.get(i).missingKinds());
        }
    }

    static ItemStack resultIcon(Pattern pat) {
        try {
            ItemStack it = Sky.items().create(pat.result, Math.max(1, Math.min(64, pat.amount)));
            if (pat.amount > 64) it = Icons.withLore(it, List.of("&8Makes " + pat.amount + "."));
            return it;
        } catch (RuntimeException e) {
            return Icons.of(Material.BARRIER, "&c" + pat.result, "&7This item is not registered.");
        }
    }

    /** The per-slot amounts of a pattern (id-set -> amount per slot), for "needs" hints. */
    private static Map<Pattern.Ing, Integer> perSlot(Pattern pat) {
        Map<Pattern.Ing, Integer> m = new java.util.LinkedHashMap<>();
        for (Pattern.Ing i : pat.cells) if (i != null) m.putIfAbsent(new Pattern.Ing(i.ids(), 1, i.display()), i.amount());
        return m;
    }

    boolean quickCraftUnlocked(Player p) {
        int need = mod.dials.quickCraftLevel.get();
        if (need <= 0 || !Sky.has(SkillService.class)) return true;
        if (mod.engine.bypass.contains(p.getUniqueId())) return true;
        return Sky.service(SkillService.class).level(p, SkillType.CARPENTRY) >= need;
    }

    /** The most valuable things the player can craft right now from inventory (+ sacks). */
    List<RecipeIndex.Entry> quickCraft(Player p, int n) {
        CraftEngine eng = mod.engine;
        Map<String, Integer> inv = Stock.inventory(p, mod.index);
        Map<String, Integer> sackMemo = new HashMap<>();
        boolean sacks = mod.dials.useSacks.get() && Sky.has(Features.Sacks.class);
        List<RecipeIndex.Entry> cands = new ArrayList<>();
        for (RecipeIndex.Entry e : mod.index.all()) {
            Pattern pat = e.pattern;
            // cheap pre-check: every ingredient has at least one id the player holds somewhere
            boolean possible = true;
            for (Pattern.Ing ing : pat.cells) {
                if (ing == null) continue;
                boolean any = false;
                for (String id : ing.ids()) {
                    if (inv.getOrDefault(id, 0) > 0) { any = true; break; }
                    if (sacks && sackMemo.computeIfAbsent(id, k -> {
                        try { return Sky.service(Features.Sacks.class).count(p, k); } catch (RuntimeException ex) { return 0; }
                    }) > 0) { any = true; break; }
                }
                if (!any) { possible = false; break; }
            }
            if (!possible) continue;
            Map<String, Integer> stock = eng.stockFor(p, pat, inv, sackMemo);
            if (Matcher.maxTimes(pat, stock, 1) < 1) continue;
            cands.add(e);
        }
        // Same order as Craftable Now: SkyBlock recipes first, and an unsellable result (a minion) ranked by its inputs.
        Map<String, Double> values = new HashMap<>();
        CraftMath.sortCraftable(cands, e -> e.pattern, id -> values.computeIfAbsent(id, CraftEngine::value));
        List<RecipeIndex.Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RecipeIndex.Entry c : cands) {
            if (out.size() >= n) break;
            if (!eng.unlocked(p, c)) continue;
            if (!seen.add(c.pattern.result)) continue;
            if (Sky.items().def(c.pattern.result) == null) continue;
            out.add(c);
        }
        return out;
    }

    private void returnGrid(Player p, Menu m) {
        Inventory inv = m.getInventory();
        int moved = 0;
        for (int s : GRID) {
            ItemStack it = inv.getItem(s);
            if (it == null || it.getType().isAir()) continue;
            inv.setItem(s, null);
            Map<Integer, ItemStack> over = p.getInventory().addItem(it);
            for (ItemStack o : over.values()) Sky.loot().dropFor(p, p.getLocation(), o);
            moved++;
        }
        if (moved == 0) Msg.error(p, "The grid is already empty!");
    }

    static String displayName(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? id : Text.strip(d.name);
    }
}
