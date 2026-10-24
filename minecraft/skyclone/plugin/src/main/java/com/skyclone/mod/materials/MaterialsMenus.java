package com.skyclone.mod.materials;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Materials Catalogue menus:
 * <pre>
 *   /materials        Materials Catalogue (families, guide, compress, search)
 *     family          paged list of that family's materials
 *     detail          one material: its recipe as a 3x3 preview, unlock state, compress buttons,
 *                     its whole compression chain with raw cost and NPC value at every step
 *   Compression Guide every chain from its raw material up
 *   /compress         everything you can compress from your inventory right now
 * </pre>
 */
public final class MaterialsMenus {

    static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};

    private final MaterialsModule module;

    MaterialsMenus(MaterialsModule module) { this.module = module; }

    private MaterialsCatalogue cat() { return module.catalogue(); }

    // =========================================================================================
    // main
    // =========================================================================================

    public void openMain(Player p) { buildMain(p).open(p); }

    Menu buildMain(Player p) {
        Menu m = new Menu("Materials Catalogue", 6).chrome();
        int total = cat().defs.size();
        m.set(4, Icons.of(Material.BOOKSHELF, "&aMaterials Catalogue",
                "&7Every raw material, block and", "&7compressed material in the game,",
                "&7with what it is made from, what it", "&7compresses into and what it sells for.",
                "", "&7Materials: &a" + Text.commasInt(total),
                "&7Compression chains: &a" + Text.commasInt(cat().chainRoots().size())));
        int[] slots = {19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < MaterialsCatalogue.FAMILIES.size() && i < slots.length; i++) {
            String fam = MaterialsCatalogue.FAMILIES.get(i);
            List<String> ids = cat().family(fam);
            m.set(slots[i], Icons.of(familyIcon(fam), "&a" + familyName(fam),
                    "&7" + familyBlurb(fam), "", "&7Materials: &a" + ids.size(), "", "&eClick to browse!"),
                    c -> openFamily(c.player(), fam, 0));
        }
        m.set(38, Icons.of(Material.KNOWLEDGE_BOOK, "&aCompression Guide",
                "&7Every compression chain, from the", "&7raw material to its final form,",
                "&7with the raw cost of each step.", "", "&eClick to view!"),
                c -> openGuide(c.player(), 0));
        m.set(40, Icons.of(Material.ANVIL, "&aCompress Materials",
                "&7Turn the raw materials in your", "&7inventory into their enchanted", "&7forms in one click.",
                "", module.dialCompressEnabled().get() ? "&eClick to open!" : "&cDisabled on this server."),
                c -> openQuick(c.player(), 0));
        m.set(42, Icons.of(Material.OAK_SIGN, "&aSearch",
                "&7Find a material by name.", "", "&eClick to search!"),
                c -> askSearch(c.player()));
        if (Sky.has(Features.SkyMenu.class)) {
            m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        }
        return m;
    }

    static Material familyIcon(String fam) {
        return switch (fam) {
            case "farming" -> Material.GOLDEN_HOE;
            case "mining" -> Material.STONE_PICKAXE;
            case "combat" -> Material.STONE_SWORD;
            case "foraging" -> Material.JUNGLE_SAPLING;
            case "fishing" -> Material.FISHING_ROD;
            case "blocks" -> Material.BRICKS;
            default -> Material.CHEST;
        };
    }

    static String familyName(String fam) {
        return switch (fam) {
            case "farming" -> "Farming Materials";
            case "mining" -> "Mining Materials";
            case "combat" -> "Combat Materials";
            case "foraging" -> "Foraging Materials";
            case "fishing" -> "Fishing Materials";
            case "blocks" -> "Blocks and Vanilla Items";
            default -> "Other Materials";
        };
    }

    static String familyBlurb(String fam) {
        return switch (fam) {
            case "farming" -> "Crops, animal goods and their compressions.";
            case "mining" -> "Ores, metals, gemstones and their compressions.";
            case "combat" -> "Monster drops and their compressions.";
            case "foraging" -> "Logs, wood and their compressions.";
            case "fishing" -> "Fish, sea drops and their compressions.";
            case "blocks" -> "Building blocks, food, tools and other everyday items.";
            default -> "Event, boss and special materials.";
        };
    }

    // =========================================================================================
    // lists
    // =========================================================================================

    public void openFamily(Player p, String fam, int page) {
        List<String> ids = new ArrayList<>(cat().family(fam));
        Sky.menus().paged(familyName(fam), ids, this::listIcon,
                pc -> openDetail(pc.click().player(), pc.entry(), () -> pagedFamily(fam, pc.page())),
                menu -> decorateList(menu),
                page).open(p);
    }

    private Menu pagedFamily(String fam, int page) {
        return Sky.menus().paged(familyName(fam), new ArrayList<>(cat().family(fam)), this::listIcon,
                pc -> openDetail(pc.click().player(), pc.entry(), () -> pagedFamily(fam, pc.page())),
                this::decorateList, page);
    }

    private void decorateList(Menu menu) {
        menu.set(48, Icons.back("Materials Catalogue"), c -> openMain(c.player()));
        menu.set(50, Icons.of(Material.OAK_SIGN, "&aSearch", "&7Find a material by name.", "", "&eClick to search!"),
                c -> askSearch(c.player()));
    }

    void askSearch(Player p) {
        p.closeInventory();
        Sky.menus().input(p, "Search materials", s -> {
            if (s == null || s.isBlank()) { openMain(p); return; }
            openSearch(p, s, 0);
        });
    }

    public void openSearch(Player p, String query, int page) {
        String q = Text.strip(query).trim().toLowerCase(Locale.ROOT);
        if (q.length() > 40) q = q.substring(0, 40);
        final String fq = q;
        List<String> hits = new ArrayList<>();
        for (ItemDef d : cat().defs.values()) {
            String name = displayName(d.id).toLowerCase(Locale.ROOT);
            if (name.contains(fq) || d.id.toLowerCase(Locale.ROOT).contains(fq.replace(' ', '_'))) hits.add(d.id);
        }
        Sky.menus().paged("Search: " + (fq.length() > 18 ? fq.substring(0, 18) : fq), hits, this::listIcon,
                pc -> openDetail(pc.click().player(), pc.entry(), () -> searchMenu(fq, pc.page())),
                this::decorateList, page).open(p);
    }

    private Menu searchMenu(String q, int page) {
        List<String> hits = new ArrayList<>();
        for (ItemDef d : cat().defs.values()) {
            if (displayName(d.id).toLowerCase(Locale.ROOT).contains(q) || d.id.toLowerCase(Locale.ROOT).contains(q.replace(' ', '_'))) hits.add(d.id);
        }
        return Sky.menus().paged("Search: " + (q.length() > 18 ? q.substring(0, 18) : q), hits, this::listIcon,
                pc -> openDetail(pc.click().player(), pc.entry(), () -> searchMenu(q, pc.page())),
                this::decorateList, page);
    }

    public void openGuide(Player p, int page) { guideMenu(page).open(p); }

    private Menu guideMenu(int page) {
        return Sky.menus().paged("Compression Guide", cat().chainRoots(), this::guideIcon,
                pc -> openDetail(pc.click().player(), firstStep(pc.entry()), () -> guideMenu(pc.page())),
                menu -> menu.set(48, Icons.back("Materials Catalogue"), c -> openMain(c.player())),
                page);
    }

    private String firstStep(String root) {
        List<String> n = cat().next(root);
        return n.isEmpty() ? root : n.get(0);
    }

    private ItemStack guideIcon(String root) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Compression chain:");
        List<String> chain = chain(root);
        for (int i = 1; i < chain.size(); i++) {
            String id = chain.get(i);
            double raw = rawOf(id, root);
            lore.add(" &8➜ " + nameOf(id) + " &8(" + Text.commasInt(raw) + "x)");
        }
        lore.add("");
        lore.add("&eClick to view!");
        return Icons.withLore(icon(root, 1), lore);
    }

    // =========================================================================================
    // quick compress
    // =========================================================================================

    public void openQuick(Player p, int page) { quickMenu(p, page).open(p); }

    private Menu quickMenu(Player p, int page) {
        List<Recipe> doable = new ArrayList<>();
        if (module.dialCompressEnabled().get()) {
            for (String id : cat().compressedItems()) {
                if (!Sky.items().exists(id)) continue;
                for (Recipe r : Compressor.recipesFor(id)) {
                    if (!module.compressor().isCompression(r)) continue;
                    if (!Sky.recipes().unlocked(p, r)) continue;
                    if (module.compressor().craftable(p, r, 1) > 0) { doable.add(r); break; }
                }
            }
        }
        int max = module.dialMaxPerClick().get();
        return Sky.menus().paged("Compress Materials", doable, r -> {
                    int can = module.compressor().craftable(p, r, max);
                    List<String> lore = new ArrayList<>();
                    lore.add("&7Uses:");
                    for (Map.Entry<String, Integer> e : ChainMath.ingredients(r, Compressor::known).entrySet()) {
                        lore.add(" &a" + Text.commasInt(e.getValue()) + "x " + nameOf(e.getKey()));
                    }
                    lore.add("");
                    lore.add("&7You can make: &a" + Text.commasInt((long) can * Math.max(1, r.amount)) + "x"
                            + (can >= max ? " &8(per click)" : ""));
                    lore.add("");
                    lore.add("&eLeft-click to compress once!");
                    lore.add("&eRight-click to compress all!");
                    return Icons.withLore(icon(r.result, r.amount), lore);
                },
                pc -> {
                    Player pl = pc.click().player();
                    Compressor.Result res = module.compressor().compress(pl, pc.entry(), pc.click().right() ? max : 1);
                    module.tell(pl, res);
                    quickMenu(pl, pc.page()).open(pl);
                },
                menu -> {
                    menu.set(48, Icons.back("Materials Catalogue"), c -> openMain(c.player()));
                    if (doable.isEmpty()) {
                        menu.set(22, Icons.of(Material.GRAY_DYE, "&7Nothing to compress",
                                "&7Gather more raw materials, or", "&7unlock more recipes by raising",
                                "&7your collections."));
                    }
                },
                page);
    }

    // =========================================================================================
    // detail
    // =========================================================================================

    public void openDetail(Player p, String id, Supplier<Menu> back) { openDetail(p, id, back, 0); }

    void openDetail(Player p, String id, Supplier<Menu> back, int recipeIndex) {
        buildDetail(p, id, back, recipeIndex).open(p);
    }

    Menu buildDetail(Player p, String id, Supplier<Menu> back, int recipeIndex) {
        String title = Text.strip(displayName(id));
        Menu m = new Menu(title.length() > 30 ? title.substring(0, 30) : title, 6).chrome();
        ItemDef d = Sky.items().def(id);
        // header
        List<String> info = new ArrayList<>();
        info.add("");
        String fam = cat().familyOf(id);
        if (fam != null) info.add("&7Category: &a" + familyName(fam));
        double sell = d == null ? 0 : d.npcSell;
        info.add(sell > 0 ? "&7NPC Sell Price: &6" + Text.commas(sell) + " coin" + (sell == 1 ? "" : "s") : "&7NPC Sell Price: &cCannot be sold");
        if (Sky.has(Features.Bazaar.class) && Sky.service(Features.Bazaar.class).isBazaarItem(id)) {
            info.add("&7Bazaar: &aTradeable");
        }
        m.set(4, Icons.withLore(icon(id, 1), info));

        // recipe preview
        List<Recipe> recipes = module.liveRecipesFor(id);
        Recipe r = recipes.isEmpty() ? null : recipes.get(Math.floorMod(recipeIndex, recipes.size()));
        if (r == null) {
            for (int s : GRID) m.set(s, Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, " "));
            m.set(23, Icons.of(Material.BARRIER, "&cNo recipe", "&7This material is gathered,", "&7dropped or bought, not crafted."));
        } else {
            drawGrid(m, r, back);
            boolean unlocked = p != null && Sky.recipes().unlocked(p, r);   // p is null only in the self-test
            List<String> st = new ArrayList<>();
            st.add("&7Makes &a" + r.amount + "x " + nameOf(r.result));
            if (r.unlock != null && !r.unlock.isBlank()) {
                st.add("&7Requires: " + safeDescribe(r.unlock));
            }
            st.add("");
            st.add(unlocked ? "&aRecipe unlocked!" : "&cRecipe locked!");
            m.set(23, Icons.of(unlocked ? Material.ARROW : Material.RED_STAINED_GLASS_PANE, unlocked ? "&aRecipe" : "&cRecipe", st));
            m.set(25, icon(r.result, r.amount));
            if (recipes.size() > 1) {
                final int next = recipeIndex + 1;
                m.set(16, Icons.of(Material.HOPPER, "&aOther Recipes",
                        "&7This material has &a" + recipes.size() + " &7recipes.",
                        "&7Showing &a" + (Math.floorMod(recipeIndex, recipes.size()) + 1) + "&7.", "", "&eClick to cycle!"),
                        c -> openDetail(c.player(), id, back, next));
            }
            // compress buttons
            if (module.dialCompressEnabled().get()) {
                int max = module.dialMaxPerClick().get();
                int can = unlocked ? module.compressor().craftable(p, r, max) : 0;
                final Recipe fr = r;
                final int idx = recipeIndex;
                m.set(34, Icons.of(can > 0 ? Material.LIME_DYE : Material.GRAY_DYE, can > 0 ? "&aCraft from Inventory" : "&cCraft from Inventory",
                        "&7Uses the materials in your", "&7inventory" + (module.dialFromSacks().get() ? " and sacks" : "") + ".",
                        "", "&7You can craft: " + (can > 0 ? "&a" : "&c") + can + "x" + (can >= max ? " &8(per click)" : ""),
                        "", can > 0 ? "&eLeft-click to craft one!" : (unlocked ? "&cNot enough materials!" : "&cRecipe locked!"),
                        can > 0 ? "&eRight-click to craft all!" : ""),
                        c -> {
                            Compressor.Result res = module.compressor().compress(c.player(), fr, c.right() ? max : 1);
                            module.tell(c.player(), res);
                            openDetail(c.player(), id, back, idx);
                        });
            }
        }

        // chain strip
        String root = cat().root(id);
        List<String> chain = chain(root);
        if (chain.size() > 1) {
            int start = 40 - Math.min(7, chain.size()) / 2;
            for (int i = 0; i < chain.size() && i < 7; i++) {
                String cid = chain.get(i);
                List<String> lore = new ArrayList<>();
                lore.add("");
                if (i > 0) lore.add("&7Raw cost: &a" + Text.commas(rawOf(cid, root)) + "x " + nameOf(root));
                double v = priceOf(cid);
                if (v > 0) lore.add("&7Sells for: &6" + Text.commas(v) + " coins");
                lore.add("");
                lore.add(cid.equals(id) ? "&8(you are here)" : "&eClick to view!");
                ItemStack it = Icons.withLore(icon(cid, 1), lore);
                if (cid.equals(id)) Icons.glow(it);
                m.set(start + i, it, cid.equals(id) ? null : c -> openDetail(c.player(), cid, back));
            }
        }

        // other doors
        if (Sky.commands() != null) {
            m.set(50, Icons.of(Material.BOOK, "&aRecipe Book", "&7See every recipe that uses or", "&7makes this material.", "", "&eClick to open!"),
                    c -> {
                        if (!Sky.commands().run(c.player(), "recipe", id)) Msg.error(c.player(), "The recipe book is not available.");
                    });
        }
        if (Sky.has(Features.Bazaar.class) && Sky.service(Features.Bazaar.class).isBazaarItem(id)) {
            m.set(52, Icons.of(Material.GOLD_BLOCK, "&6Bazaar", "&7Buy or sell this material.", "", "&eClick to open!"),
                    c -> Sky.service(Features.Bazaar.class).open(c.player(), id));
        }
        if (back != null) m.set(48, Icons.back("Previous Menu"), c -> back.get().open(c.player()));
        else m.set(48, Icons.back("Materials Catalogue"), c -> openMain(c.player()));
        return m;
    }

    private void drawGrid(Menu m, Recipe r, Supplier<Menu> back) {
        for (int i = 0; i < 9; i++) {
            String raw = r.grid != null && i < r.grid.length ? r.grid[i] : null;
            ChainMath.Cell c = ChainMath.cell(raw, Compressor::known);
            if (c == null) { m.set(GRID[i], Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, " ")); continue; }
            ItemStack it = Icons.withLore(icon(c.id(), c.amount()), List.of("", "&7Needed: &a" + c.amount() + "x",
                    cat().has(c.id()) ? "&eClick to view!" : ""));
            final String cid = c.id();
            m.set(GRID[i], it, cat().has(cid) ? cl -> openDetail(cl.player(), cid, back) : null);
        }
    }

    // =========================================================================================
    // helpers
    // =========================================================================================

    private ItemStack listIcon(String id) {
        List<String> lore = new ArrayList<>();
        lore.add("");
        double sell = priceOf(id);
        if (sell > 0) lore.add("&7NPC Sell Price: &6" + Text.commas(sell));
        String prev = cat().previous(id);
        if (prev != null) lore.add("&7Made from: " + nameOf(prev));
        List<String> next = cat().next(id);
        if (!next.isEmpty()) lore.add("&7Compresses into: " + nameOf(next.get(0)));
        lore.add("");
        lore.add("&eClick to view details!");
        return Icons.withLore(icon(id, 1), lore);
    }

    static ItemStack icon1(String id) { return icon(id, 1); }

    /** A real sky stack for display, or a barrier when the id is not registered. */
    static ItemStack icon(String id, int amount) {
        try {
            if (Sky.items().exists(id)) {
                ItemStack it = Sky.items().create(id, 1);
                int max = Math.max(1, it.getMaxStackSize());
                it.setAmount(Math.max(1, Math.min(Math.min(amount, 99), max)));
                return it;
            }
        } catch (RuntimeException ignored) {
            // fall through to the placeholder
        }
        return Icons.of(Material.BARRIER, "&c" + id, "&7This item is not registered.");
    }

    static String displayName(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null || d.name == null ? id : Text.strip(d.name);
    }

    static String nameOf(String id) {
        ItemDef d = Sky.items().def(id);
        if (d == null) return "&c" + id;
        return (d.rarity == null ? "&f" : d.rarity.code()) + Text.strip(d.name);
    }

    static double priceOf(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? 0 : d.npcSell;
    }

    static String safeDescribe(String key) {
        try {
            String s = Sky.requirements().describe(key);
            return s == null || s.isBlank() ? "&a" + key : s;
        } catch (RuntimeException e) {
            return "&a" + key;
        }
    }

    List<String> chain(String root) {
        return ChainMath.chainUp(root, cat()::next, 8);
    }

    /** Raw cost of one {@code id} in units of {@code root}, following the catalogue's chain recipes. */
    double rawOf(String id, String root) {
        Map<String, Double> raw = ChainMath.rawCost(id, chainRecipe(), Compressor::known, java.util.Set.of(root));
        Double v = raw.get(root);
        return v == null ? 0 : v;
    }

    /** The recipe that makes x out of the previous step of its chain (live registry first, data second). */
    Function<String, Recipe> chainRecipe() {
        return x -> {
            String prev = cat().previous(x);
            if (prev == null) return null;
            for (Recipe r : Compressor.recipesFor(x)) {
                if (prev.equals(ChainMath.mainInput(r, Compressor::known))) return r;
            }
            for (Recipe r : cat().dataRecipesFor(x)) {
                if (prev.equals(ChainMath.mainInput(r, Compressor::known))) return r;
            }
            return null;
        };
    }
}
