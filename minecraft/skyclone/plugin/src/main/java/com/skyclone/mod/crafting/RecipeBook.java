package com.skyclone.mod.crafting;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The Recipe Book: categories -> items -> a recipe view with a 3x3 preview (research/08 §11.5:
 * grid 10-12/19-21/28-30, arrow 23, result 25, Supercraft 32), plus search, "Used In", what you
 * can craft right now, recent crafts, favourites, a raw-material breakdown and a value readout.
 */
final class RecipeBook {

    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final Map<Category, Integer> CAT_SLOTS = new EnumMap<>(Category.class);
    static {
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 29, 30, 31, 32, 33};
        Category[] cs = Category.values();
        for (int i = 0; i < cs.length && i < slots.length; i++) CAT_SLOTS.put(cs[i], slots[i]);
    }
    private static final List<String> VANILLA_FILTERS = List.of("all", "building", "equipment", "redstone", "misc");

    private final CraftingModule mod;

    RecipeBook(CraftingModule mod) { this.mod = mod; }

    private CraftEngine eng() { return mod.engine; }
    private RecipeIndex idx() { return mod.index; }

    // ---- root ------------------------------------------------------------------------------------------

    void openRoot(Player p) { root(p).open(p); }

    Menu root(Player p) {
        Menu m = new Menu("Recipe Book", 6);
        m.chrome();
        Map<Category, int[]> counts = new EnumMap<>(Category.class);     // [unlocked, total]
        int un = 0, tot = 0;
        for (RecipeIndex.Entry e : idx().all()) {
            int[] c = counts.computeIfAbsent(e.category, k -> new int[2]);
            c[1]++;
            boolean ok = eng().unlocked(p, e);
            if (ok) c[0]++;
            if (e.category != Category.VANILLA) { tot++; if (ok) un++; }
        }
        CraftData d = eng().data(p);
        double pct = tot == 0 ? 0 : 100.0 * un / tot;
        if (d != null) { d.bookUnlocked = un; d.bookTotal = tot; d.bookUnlockedPct = Math.round(pct * 10) / 10.0; }
        List<String> head = new ArrayList<>();
        head.add("&7Every recipe in the game, sorted by");
        head.add("&7what unlocks it. Grow your collections");
        head.add("&7and skills to unlock more.");
        head.add("");
        head.add("&7Recipe Book Unlocked: &e" + Text.commas(Math.round(pct * 10) / 10.0) + "&6%");
        head.add(Text.bar(pct / 100.0, 20) + " &e" + Text.commas(un) + "&6/&e" + Text.commas(tot));
        if (d != null) {
            head.add("");
            head.add("&7Items crafted: &a" + Text.commas(d.crafted));
            if (d.carpentryXp > 0) head.add("&7Carpentry XP from crafting: &3" + Text.shortNum(d.carpentryXp));
        }
        m.set(4, Icons.of(Material.BOOK, "&aRecipe Book", head));

        for (Category c : Category.values()) {
            Integer slot = CAT_SLOTS.get(c);
            if (slot == null) continue;
            int[] n = counts.getOrDefault(c, new int[2]);
            List<String> lore = new ArrayList<>();
            lore.add("&7" + c.blurb);
            lore.add("");
            double f = n[1] == 0 ? 0 : (double) n[0] / n[1];
            lore.add("&7Unlocked: &e" + Text.commas(Math.round(f * 1000) / 10.0) + "&6%");
            lore.add(Text.bar(f, 20) + " &e" + n[0] + "&6/&e" + n[1]);
            lore.add("");
            lore.add(n[1] == 0 ? "&8No recipes here yet." : "&eClick to view!");
            m.set(slot, Icons.of(c.icon, c.color + c.display + " Recipes", lore),
                    n[1] == 0 ? null : cl -> category(cl.player(), c, 0).open(cl.player()));
        }

        m.set(39, Icons.of(Material.OAK_SIGN, "&aSearch Recipes", "&7Find any recipe by item name.", "", "&eClick to search!"),
                c -> search(c.player()));
        m.set(40, Icons.of(Material.EMERALD, "&aTrades", "&7Swap common materials for others.", "&7Collections unlock new trades.", "", "&eClick to view!"),
                c -> mod.trades.open(c.player()));
        m.set(41, Icons.of(Material.CRAFTING_TABLE, "&aCraftable Now", "&7Everything you can make right now", "&7from your inventory" + (mod.dials.useSacks.get() ? " and sacks" : "") + ".", "&8SkyBlock recipes first, vanilla last.", "", "&eClick to view!"),
                c -> craftable(c.player(), 0).open(c.player()));
        int recent = d == null ? 0 : d.recent.size(), favs = d == null ? 0 : d.favorites.size();
        m.set(45, Icons.of(Material.CLOCK, "&aRecently Crafted", "&7The last things you crafted.", "", "&7Remembered: &a" + recent, "", "&eClick to view!"),
                c -> list(c.player(), "Recently Crafted", recentList(c.player()), 0, () -> root(c.player())).open(c.player()));
        m.set(46, Icons.of(Material.NETHER_STAR, "&aFavorites", "&7Recipes you pinned. Right-click", "&7any recipe's result to pin it.", "", "&7Pinned: &a" + favs, "", "&eClick to view!"),
                c -> list(c.player(), "Favorite Recipes", favList(c.player()), 0, () -> root(c.player())).open(c.player()));
        m.set(50, Icons.of(Material.CRAFTING_TABLE, "&aCrafting Table", "&7Open a 3x3 crafting grid.", "", "&eClick to open!"),
                c -> mod.craftMenu.open(c.player()));
        if (Sky.has(Features.SkyMenu.class)) {
            m.set(m.closeSlot() - 1, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        }
        return m;
    }

    /** Refresh the stored Recipe Book progress (cheap: one requirement check per recipe). */
    void updateProgress(Player p) {
        CraftData d = eng().data(p);
        if (d == null) return;
        int un = 0, tot = 0;
        for (RecipeIndex.Entry e : idx().all()) {
            if (e.category == Category.VANILLA) continue;
            tot++;
            if (eng().unlocked(p, e)) un++;
        }
        d.bookUnlocked = un;
        d.bookTotal = tot;
        d.bookUnlockedPct = tot == 0 ? 0 : Math.round(1000.0 * un / tot) / 10.0;
    }

    private List<String> recentList(Player p) {
        CraftData d = eng().data(p);
        return d == null ? List.of() : new ArrayList<>(d.recent);
    }

    private List<String> favList(Player p) {
        CraftData d = eng().data(p);
        return d == null ? List.of() : new ArrayList<>(d.favorites);
    }

    // ---- a category ----------------------------------------------------------------------------------

    Menu category(Player p, Category cat, int page) {
        CraftData d = eng().data(p);
        boolean hide = d != null && d.bookHideLocked;
        String vf = d == null || !VANILLA_FILTERS.contains(d.vanillaFilter) ? "all" : d.vanillaFilter;
        List<String> results = new ArrayList<>();
        Map<String, Boolean> open = new HashMap<>();
        for (String id : idx().results(cat)) {
            boolean any = false, inFilter = cat != Category.VANILLA || vf.equals("all");
            for (RecipeIndex.Entry e : idx().producing(id)) {
                if (e.category != cat) continue;
                if (!inFilter && vf.equals(e.vanillaCat)) inFilter = true;
                if (eng().unlocked(p, e)) any = true;
            }
            if (!inFilter) continue;
            if (hide && !any) continue;
            open.put(id, any);
            results.add(id);
        }
        // unlocked first, then by name (already name-sorted in the index)
        results.sort(Comparator.comparing((String id) -> !open.getOrDefault(id, false)));
        String title = cat.display + " Recipes" + (cat == Category.VANILLA && !vf.equals("all") ? " (" + vf + ")" : "");
        return Sky.menus().paged(title, results,
                id -> bookIcon(p, id, cat, open.getOrDefault(id, false)),
                pc -> {
                    if (pc.click().right()) { toggleFavorite(pc.click().player(), pc.entry()); category(pc.click().player(), cat, pc.page()).open(pc.click().player()); return; }
                    view(pc.click().player(), pc.entry(), firstIn(pc.entry(), cat), () -> category(pc.click().player(), cat, pc.page())).open(pc.click().player());
                },
                menu -> {
                    menu.back("Recipe Book", () -> root(p));
                    menu.set(50, Icons.of(hide ? Material.ENDER_EYE : Material.ENDER_PEARL, "&aLocked Recipes: " + (hide ? "&cHidden" : "&aShown"),
                            "&7Toggle whether recipes you have", "&7not unlocked yet are listed.", "", "&eClick to toggle!"), c -> {
                        CraftData dd = eng().data(c.player());
                        if (dd != null) dd.bookHideLocked = !dd.bookHideLocked;
                        category(c.player(), cat, 0).open(c.player());
                    });
                    if (cat == Category.VANILLA) {
                        List<String> lore = new ArrayList<>();
                        lore.add("&7Show only one kind of vanilla recipe.");
                        lore.add("");
                        for (String f : VANILLA_FILTERS) lore.add((f.equals(vf) ? "&b▶ " : "&7  ") + Text.titleCase(f));
                        lore.add("");
                        lore.add("&eClick to switch!");
                        menu.set(51, Icons.of(Material.HOPPER, "&aFilter", lore), c -> {
                            CraftData dd = eng().data(c.player());
                            if (dd != null) dd.vanillaFilter = VANILLA_FILTERS.get((VANILLA_FILTERS.indexOf(vf) + 1) % VANILLA_FILTERS.size());
                            category(c.player(), cat, 0).open(c.player());
                        });
                    }
                    menu.set(47, Icons.of(Material.OAK_SIGN, "&aSearch Recipes", "&eClick to search!"), c -> search(c.player()));
                }, page);
    }

    private int firstIn(String id, Category cat) {
        List<RecipeIndex.Entry> l = idx().producing(id);
        for (int i = 0; i < l.size(); i++) if (l.get(i).category == cat) return i;
        return 0;
    }

    private ItemStack bookIcon(Player p, String id, Category cat, boolean unlocked) {
        ItemDef d = Sky.items().def(id);
        if (!unlocked) {
            String req = "";
            for (RecipeIndex.Entry e : idx().producing(id)) {
                if (cat != null && e.category != cat) continue;
                req = eng().lockText(e);
                break;
            }
            List<String> lore = new ArrayList<>();
            lore.add("&cLocked");
            if (!req.isEmpty()) lore.add("&7Requires " + req + "&7.");
            lore.add("");
            lore.add("&eClick to view recipe!");
            return Icons.of(Material.GRAY_DYE, "&c" + (d == null ? id : Text.strip(d.name)), lore);
        }
        ItemStack it;
        try { it = Sky.items().create(id, 1); } catch (RuntimeException e) { return Icons.of(Material.BARRIER, "&c" + id); }
        List<String> extra = new ArrayList<>();
        extra.add("");
        int n = idx().producing(id).size();
        if (n > 1) extra.add("&8" + n + " recipes");
        CraftData cd = eng().data(p);
        if (cd != null && cd.favorites.contains(id)) extra.add("&6★ Favorite");
        extra.add("&eClick to view recipe!");
        extra.add("&eRight-click to " + (cd != null && cd.favorites.contains(id) ? "unpin" : "pin") + "!");
        return Icons.withLore(it, extra);
    }

    void toggleFavorite(Player p, String id) {
        CraftData d = eng().data(p);
        if (d == null) return;
        if (d.favorites.remove(id)) { Msg.send(p, "&eUnpinned " + CraftEngine.name(id) + "&e."); return; }
        if (d.favorites.size() >= 45) { Msg.error(p, "You can pin up to 45 recipes!"); return; }
        d.favorites.add(0, id);
        Msg.success(p, "Pinned " + CraftEngine.name(id) + "&a to your favorites!");
    }

    // ---- generic lists (recent, favourites, search, used-in) -----------------------------------------

    Menu list(Player p, String title, List<String> ids, int page, Supplier<Menu> back) {
        List<String> clean = new ArrayList<>();
        for (String id : ids) if (!idx().producing(id).isEmpty() || mod.sources.has(id)) clean.add(id);
        return Sky.menus().paged(title, clean,
                id -> idx().producing(id).isEmpty() ? sourceIcon(id) : bookIcon(p, id, null, anyUnlocked(p, id)),
                pc -> {
                    if (pc.click().right()) { toggleFavorite(pc.click().player(), pc.entry()); list(pc.click().player(), title, ids, pc.page(), back).open(pc.click().player()); return; }
                    view(pc.click().player(), pc.entry(), 0, () -> list(pc.click().player(), title, ids, pc.page(), back)).open(pc.click().player());
                },
                menu -> menu.back(Text.strip(backTitle(back)), back), page);
    }

    private static String backTitle(Supplier<Menu> back) { return "Previous Menu"; }

    /** A list entry for an item with no grid recipe but another source (forge, shop, furnace...). */
    private ItemStack sourceIcon(String id) {
        ItemStack it;
        try { it = Sky.items().create(id, 1); } catch (RuntimeException e) { return Icons.of(Material.BARRIER, "&c" + id); }
        return Icons.withLore(it, List.of("", "&8Not crafted: forged, bought or smelted.", "&eClick to see how to get it!"));
    }

    private boolean anyUnlocked(Player p, String id) {
        for (RecipeIndex.Entry e : idx().producing(id)) if (eng().unlocked(p, e)) return true;
        return false;
    }

    void search(Player p) {
        Sky.menus().input(p, "Search recipes", q -> {
            if (q == null || q.isBlank()) { openRoot(p); return; }
            String query = q.length() > 32 ? q.substring(0, 32) : q;
            List<String> hits = new ArrayList<>(idx().search(query));
            for (String h : mod.sources.search(query)) if (!hits.contains(h)) hits.add(h);
            if (hits.isEmpty()) { Msg.error(p, "No recipe makes anything called \"" + Text.strip(query) + "\"."); openRoot(p); return; }
            if (hits.size() == 1) { view(p, hits.get(0), 0, () -> root(p)).open(p); return; }
            list(p, "Search: " + Text.strip(query), hits, 0, () -> root(p)).open(p);
        });
    }

    /** /recipe <item>: exact id, then canonical id, then a name search. */
    void openFor(Player p, String query) {
        String id = query.toUpperCase(Locale.ROOT).replace(' ', '_');
        if (idx().producing(id).isEmpty()) {
            // Wave 3: an exact id with no grid recipe opens its How to Get page (forge, shop, furnace, bazaar...).
            if (Sky.items().exists(id)) { mod.sources.menu(p, id, () -> root(p)).open(p); return; }
            List<String> hits = new ArrayList<>(idx().search(query));
            for (String h : mod.sources.search(query)) if (!hits.contains(h)) hits.add(h);
            if (hits.isEmpty()) {
                Msg.error(p, "No recipe found for \"" + Text.strip(query) + "\".");
                return;
            }
            if (hits.size() > 1) {
                for (String h : hits) if (CraftMenu.displayName(h).equalsIgnoreCase(query.trim())) { view(p, h, 0, () -> root(p)).open(p); return; }
                list(p, "Search: " + Text.strip(query), hits, 0, () -> root(p)).open(p);
                return;
            }
            id = hits.get(0);
        }
        view(p, id, 0, () -> root(p)).open(p);   // view() falls through to How to Get when no grid recipe makes it
    }

    Menu usedIn(Player p, String id, int page, Supplier<Menu> back) {
        List<RecipeIndex.Entry> uses = new ArrayList<>(idx().using(id));
        Map<String, RecipeIndex.Entry> byResult = new LinkedHashMap<>();
        for (RecipeIndex.Entry e : uses) byResult.putIfAbsent(e.pattern.result, e);
        List<RecipeIndex.Entry> list = new ArrayList<>(byResult.values());
        list.sort(Comparator.comparing((RecipeIndex.Entry e) -> e.pattern.vanilla).thenComparing(e -> CraftMenu.displayName(e.pattern.result)));
        return Sky.menus().paged("Used In: " + Text.strip(CraftMenu.displayName(id)), list,
                e -> bookIcon(p, e.pattern.result, null, eng().unlocked(p, e)),
                pc -> {
                    int at = Math.max(0, idx().producing(pc.entry().pattern.result).indexOf(pc.entry()));
                    view(pc.click().player(), pc.entry().pattern.result, at, () -> usedIn(pc.click().player(), id, pc.page(), back)).open(pc.click().player());
                },
                menu -> menu.back("Recipe", back), page);
    }

    Menu craftable(Player p, int page) {
        Map<String, Integer> inv = Stock.inventory(p, idx());
        Map<String, Integer> memo = new HashMap<>();
        Map<String, RecipeIndex.Entry> best = new LinkedHashMap<>();
        for (RecipeIndex.Entry e : idx().all()) {
            Pattern pat = e.pattern;
            boolean possible = true;
            for (Pattern.Ing ing : pat.cells) {
                if (ing == null) continue;
                boolean any = false;
                for (String id : ing.ids()) if (inv.getOrDefault(id, 0) > 0) { any = true; break; }
                if (!any && !(mod.dials.useSacks.get() && Sky.has(Features.Sacks.class))) { possible = false; break; }
            }
            if (!possible) continue;
            Map<String, Integer> stock = eng().stockFor(p, pat, inv, memo);
            if (Matcher.maxTimes(pat, stock, 1) < 1) continue;
            if (!eng().unlocked(p, e)) continue;
            if (best.containsKey(pat.result)) continue;
            best.put(pat.result, e);
        }
        List<RecipeIndex.Entry> list = new ArrayList<>(best.values());
        // SkyBlock recipes (minions, enchanted items, gear) first, then vanilla; unsellable results ranked by inputs.
        Map<String, Double> values = new HashMap<>();
        CraftMath.sortCraftable(list, e -> e.pattern, id -> values.computeIfAbsent(id, CraftEngine::value));
        return Sky.menus().paged("Craftable Now", list,
                e -> {
                    ItemStack it = CraftMenu.resultIcon(e.pattern);
                    Map<String, Integer> st = eng().stockFor(p, e.pattern, inv, memo);
                    List<String> lore = new ArrayList<>();
                    lore.add("");
                    lore.addAll(eng().checklist(e.pattern, st, 1));
                    lore.add("");
                    lore.add("&eClick to view recipe!");
                    lore.add("&eShift-click to craft max!");
                    return Icons.withLore(it, lore);
                },
                pc -> {
                    if (pc.click().shift()) {
                        eng().craftStock(pc.click().player(), pc.entry(), Integer.MAX_VALUE, true);
                        craftable(pc.click().player(), pc.page()).open(pc.click().player());
                        return;
                    }
                    int at = Math.max(0, idx().producing(pc.entry().pattern.result).indexOf(pc.entry()));
                    view(pc.click().player(), pc.entry().pattern.result, at, () -> craftable(pc.click().player(), pc.page())).open(pc.click().player());
                },
                menu -> menu.back("Recipe Book", () -> root(p)), page);
    }

    // ---- one recipe -------------------------------------------------------------------------------------

    Menu view(Player p, String resultId, int index, Supplier<Menu> back) {
        List<RecipeIndex.Entry> all = idx().producing(resultId);
        if (all.isEmpty()) return mod.sources.menu(p, resultId, back);   // forged / bought / smelted / dropped
        int at = Math.floorMod(index, all.size());
        RecipeIndex.Entry e = all.get(at);
        Pattern pat = e.pattern;
        String title = CraftMenu.displayName(resultId);
        if (title.length() > 24) title = title.substring(0, 24);
        Menu m = new Menu(title + " Recipe" + (all.size() > 1 ? " (" + (at + 1) + "/" + all.size() + ")" : ""), 6);
        m.chrome();
        boolean open = eng().unlocked(p, e);
        Map<String, Integer> inv = p == null ? Map.of() : Stock.inventory(p, idx());
        Map<String, Integer> stock = p == null ? new HashMap<>() : eng().stockFor(p, pat, inv, null);
        Supplier<Menu> self = () -> view(p, resultId, at, back);

        Pattern.Ing[] g9 = pat.grid9();
        for (int i = 0; i < 9; i++) {
            Pattern.Ing ing = g9[i];
            if (ing == null) continue;
            m.set(GRID[i], ingredientIcon(ing, stock), c -> {
                String id = ing.display();
                if (!idx().producing(id).isEmpty()) view(c.player(), id, 0, self).open(c.player());
                else mod.sources.menu(c.player(), id, self).open(c.player());
            });
        }
        if (pat.shapeless) m.set(13, Icons.of(Material.PAPER, "&eShapeless", "&7These ingredients can go", "&7anywhere in the grid."));
        m.set(23, Icons.of(Material.ARROW, "&a→", "&7Crafts into"));

        int can = p == null ? 0 : Matcher.maxTimes(pat, stock, Math.max(1, mod.dials.superCraftCap.get()));
        List<String> rl = new ArrayList<>();
        rl.add("");
        if (pat.amount > 1) rl.add("&7Makes &a" + pat.amount + "x &7per craft.");
        CraftData cd = eng().data(p);
        boolean fav = cd != null && cd.favorites.contains(resultId);
        if (!open) {
            rl.add("&cRecipe locked!");
            rl.add("&7Requires " + eng().lockText(e) + "&7.");
        } else if (can > 0) {
            rl.add("&eClick to craft!");
            if (can > 1) rl.add("&eShift-click to craft max! &8(" + can + ")");
        } else {
            rl.add("&cYou don't have the materials.");
        }
        rl.add("&eRight-click to " + (fav ? "unpin" : "pin") + "!");
        m.set(25, Icons.withLore(CraftMenu.resultIcon(pat), rl), c -> {
            if (c.right()) { toggleFavorite(c.player(), resultId); self.get().open(c.player()); return; }
            if (eng().craftStock(c.player(), e, c.shift() ? Integer.MAX_VALUE : 1, true) > 0) self.get().open(c.player());
        });

        // unlock state
        if (e.recipe.unlock == null) {
            m.set(14, Icons.of(Material.LIME_DYE, "&aAlways Unlocked", "&7Anyone can craft this."));
        } else {
            m.set(14, Icons.of(open ? Material.LIME_DYE : Material.GRAY_DYE, open ? "&aRecipe Unlocked" : "&cRecipe Locked",
                    "&7Requires " + eng().lockText(e) + "&7.", "", open ? "&aYou meet this requirement." : "&cYou don't meet this yet."));
        }

        // supercraft
        List<String> sl = new ArrayList<>();
        sl.add("&7Craft this straight from your");
        sl.add("&7inventory" + (mod.dials.useSacks.get() ? " and sacks" : "") + ", no grid needed.");
        sl.add("");
        sl.addAll(eng().checklist(pat, stock, 1));
        sl.add("");
        if (!open) sl.add("&cRecipe locked!");
        else if (can <= 0) sl.add("&cMissing materials!");
        else {
            sl.add("&eLeft-click to craft x1!");
            sl.add("&eRight-click to choose an amount!");
            sl.add("&eShift-click to craft max! &8(" + can + ")");
        }
        m.set(32, Icons.of(Material.CRAFTING_TABLE, "&aSupercraft", sl), c -> {
            if (c.shift()) { eng().craftStock(c.player(), e, Integer.MAX_VALUE, true); self.get().open(c.player()); return; }
            if (c.right()) {
                Sky.menus().inputNumber(c.player(), "Craft how many?", n -> {
                    if (n == null || n < 1) { self.get().open(c.player()); return; }
                    eng().craftStock(c.player(), e, (int) Math.min(Integer.MAX_VALUE, Math.floor(n)), true);
                    self.get().open(c.player());
                });
                return;
            }
            eng().craftStock(c.player(), e, 1, true);
            self.get().open(c.player());
        });

        m.set(16, valueIcon(pat));
        m.set(34, rawIcon(p, pat));
        int used = idx().using(resultId).size();
        int stUsed = mod.sources.othersUsing(resultId).size();
        List<String> ul = new ArrayList<>(List.of("&7Recipes that use this item:", "&a" + used));
        if (stUsed > 0) ul.add("&7Plus &a" + stUsed + " &7at the Forge and other stations.");
        ul.add("");
        ul.add(used > 0 ? "&eClick to view!" : stUsed > 0 ? "&eClick to view station uses!" : "&8Nothing uses it.");
        m.set(43, Icons.of(Material.HOPPER, "&aUsed In", ul),
                used > 0 ? c -> usedIn(c.player(), resultId, 0, self).open(c.player())
                        : stUsed > 0 ? c -> mod.sources.stationUses(c.player(), resultId, 0, self).open(c.player()) : null);
        m.set(7, Icons.of(e.category.icon, e.category.color + e.category.display + " Recipe",
                "&7Category: " + e.category.color + e.category.display,
                pat.vanilla ? "&8A vanilla recipe." : "&8A SkyBlock recipe."));

        if (all.size() > 1) {
            m.set(45, Icons.of(Material.ARROW, "&aPrevious Recipe", "&7Recipe " + (Math.floorMod(at - 1, all.size()) + 1) + " of " + all.size()),
                    c -> view(c.player(), resultId, at - 1, back).open(c.player()));
            m.set(53, Icons.of(Material.ARROW, "&aNext Recipe", "&7Recipe " + (Math.floorMod(at + 1, all.size()) + 1) + " of " + all.size()),
                    c -> view(c.player(), resultId, at + 1, back).open(c.player()));
        }
        int other = mod.sources.others(resultId).size() + mod.sources.smelting(resultId).size();
        List<String> hl = new ArrayList<>(List.of("&7Every way to get this item:", "&7recipes, forge, shops, furnace,", "&7Bazaar and Auction House."));
        if (other > 0) { hl.add(""); hl.add("&7Other recipes: &a" + other); }
        hl.add("");
        hl.add("&eClick to view!");
        m.set(41, Icons.of(Material.COMPASS, "&aHow to Get", hl),
                c -> mod.sources.menu(c.player(), resultId, self).open(c.player()));
        m.set(50, Icons.of(Material.CRAFTING_TABLE, "&aCrafting Table", "&7Open a 3x3 crafting grid.", "", "&eClick to open!"),
                c -> mod.craftMenu.open(c.player()));
        if (back != null) m.back("Previous Menu", back);
        else m.back("Recipe Book", () -> root(p));
        return m;
    }

    private ItemStack ingredientIcon(Pattern.Ing ing, Map<String, Integer> stock) {
        ItemStack base;
        try { base = Sky.items().create(ing.display(), Math.max(1, Math.min(64, ing.amount()))); }
        catch (RuntimeException ex) { base = Icons.of(Material.BARRIER, "&c" + ing.display(), "&cThis item does not exist!"); }
        long have = 0;
        for (String id : ing.ids()) have += stock.getOrDefault(id, 0);
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Needed in this slot: &a" + ing.amount());
        lore.add("&7You have: " + (have >= ing.amount() ? "&a" : "&c") + Text.commas(have));
        if (ing.ids().size() > 1) {
            lore.add("");
            lore.add("&7Any of these works:");
            int n = 0;
            for (String id : ing.ids()) {
                if (n++ >= 5) { lore.add(" &8...and " + (ing.ids().size() - 5) + " more"); break; }
                lore.add(" &8- " + CraftEngine.name(id));
            }
        }
        boolean hasRecipe = !idx().producing(ing.display()).isEmpty();
        lore.add("");
        lore.add(hasRecipe ? "&eClick to view its recipe!" : "&eClick to see how to get it!");
        return Icons.withLore(base, lore);
    }

    private ItemStack valueIcon(Pattern pat) {
        List<String> lore = new ArrayList<>();
        double ingNpc = 0;
        double ingBuy = 0;
        boolean bz = Sky.has(Features.Bazaar.class);
        boolean allBz = bz;
        for (Map.Entry<Pattern.Ing, Integer> en : pat.totals().entrySet()) {
            String id = en.getKey().display();
            ingNpc += CraftEngine.npcSell(id) * en.getValue();
            if (bz) {
                try {
                    Features.Bazaar b = Sky.service(Features.Bazaar.class);
                    if (b.isBazaarItem(id)) ingBuy += b.instantBuy(id) * en.getValue(); else allBz = false;
                } catch (RuntimeException ex) { allBz = false; }
            }
        }
        double resNpc = CraftEngine.npcSell(pat.result) * pat.amount;
        double xp = eng().carpentryXp(pat);
        lore.add("&7Carpentry XP: &3+" + Text.commas(Math.round(xp * 10) / 10.0));
        String why = eng().noXpReason(pat);
        if (why != null) lore.add("&8" + why);
        lore.add("");
        lore.add("&7Ingredients sell to NPCs for: &6" + Text.commas(Math.round(ingNpc * 10) / 10.0));
        lore.add("&7Result sells to NPCs for: &6" + Text.commas(Math.round(resNpc * 10) / 10.0));
        if (bz && allBz) {
            double resSell = 0;
            try {
                Features.Bazaar b = Sky.service(Features.Bazaar.class);
                if (b.isBazaarItem(pat.result)) resSell = b.instantSell(pat.result) * pat.amount;
            } catch (RuntimeException ignored) {}
            lore.add("");
            lore.add("&7Buying ingredients (Bazaar): &6" + Text.shortNum(ingBuy));
            if (resSell > 0) {
                lore.add("&7Selling result (Bazaar): &6" + Text.shortNum(resSell));
                double profit = resSell - ingBuy;
                lore.add("&7Craft profit: " + (profit >= 0 ? "&a+" : "&c") + Text.shortNum(profit));
            }
        }
        return Icons.of(Material.GOLD_NUGGET, "&6Value", lore);
    }

    private ItemStack rawIcon(Player p, Pattern pat) {
        int depth = Math.max(1, mod.dials.treeDepth.get());
        Map<String, Long> wants = new LinkedHashMap<>();
        for (Map.Entry<Pattern.Ing, Integer> en : pat.totals().entrySet()) wants.merge(en.getKey().display(), (long) en.getValue(), Long::sum);
        Map<String, Long> raw = CraftMath.rawAll(wants, this::treeRecipe, depth - 1);
        List<String> lore = new ArrayList<>();
        lore.add("&7Everything this takes, broken down");
        lore.add("&7to the most basic materials:");
        lore.add("");
        Map<String, Integer> inv = p == null ? Map.of() : Stock.inventory(p, idx());
        int n = 0;
        List<Map.Entry<String, Long>> rows = new ArrayList<>(raw.entrySet());
        rows.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        for (Map.Entry<String, Long> en : rows) {
            if (n++ >= 14) { lore.add("&8...and " + (rows.size() - 14) + " more"); break; }
            int have = inv.getOrDefault(en.getKey(), 0);
            lore.add((have >= en.getValue() ? "&a✔ " : "&7- ") + "&f" + Text.commas(en.getValue()) + "x " + CraftEngine.name(en.getKey()));
        }
        return Icons.of(Material.CHEST, "&aRaw Materials", lore);
    }

    private Pattern treeRecipe(String id) { return treeRecipe(id, null); }

    /**
     * The recipe the raw-material tree follows ({@link CraftMath#treePick}: never vanilla, never an unpacking on a
     * crafting cycle, cheapest inputs per item made). A unique item is not decomposed unless it is {@code top}, the
     * item the tree is for. Wave 3: no grid recipe -&gt; walk through its Forge recipe.
     */
    Pattern treeRecipe(String id, String top) {
        ItemDef d = Sky.items().def(id);
        if (d != null && d.isUnique() && !id.equals(top)) return null;
        List<RecipeIndex.Entry> l = idx().producing(id);
        if (l.isEmpty()) return mod.sources.forgePattern(id);
        List<Pattern> pats = new ArrayList<>();
        for (RecipeIndex.Entry e : l) pats.add(e.pattern);
        return CraftMath.treePick(pats, idx()::reversible, p -> {
            List<Map.Entry<Set<String>, Integer>> ins = new ArrayList<>();
            for (Map.Entry<Pattern.Ing, Integer> en : p.totals().entrySet()) ins.add(Map.entry(en.getKey().ids(), en.getValue()));
            double v = SellCap.inputValue(ins, CraftEngine::npcSell);
            return v < 0 ? -1 : (v + p.coins) / Math.max(1, p.amount);
        });
    }

    static Set<String> namesOf(List<String> ids) { return new LinkedHashSet<>(ids); }
}
