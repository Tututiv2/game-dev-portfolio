package com.skyclone.mod.crafting;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * "How to Get" — every way to obtain an item that is NOT a 3x3 craft: Forge recipes, NPC shops, brewing and other
 * stations (all registered by their owners in {@code Sky.recipes()}), vanilla furnace smelting (read from the
 * server's own recipe list at boot), the Bazaar, the Auction House and collections.
 *
 * <p>Wave 3. Before this the Recipe Book answered "Nothing crafts this item." for Refined Mithril, Glass, a
 * Pickaxe Smith pickaxe or a potion, and clicking such an ingredient inside a recipe only showed what USES it —
 * so a player (or one of the show's AI players) following a recipe tree hit a dead end at the first forged or
 * smelted input. Display only: nothing here crafts, buys or forges.
 */
final class Sources {

    /** One vanilla furnace recipe, in sky ids. */
    record Smelt(String result, int amount, List<String> inputs, int cookTicks, float exp) {}

    private static final int[] SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private final CraftingModule mod;
    private volatile Map<String, List<Smelt>> smeltByResult = Map.of();
    private volatile Map<String, List<Smelt>> smeltByInput = Map.of();

    Sources(CraftingModule mod) { this.mod = mod; }

    // ---- boot ------------------------------------------------------------------------------------------

    /** Read the server's furnace recipes (first tick, after materials registered the vanilla-equivalent items). */
    int loadFurnace() {
        Map<String, List<Smelt>> byRes = new HashMap<>(), byIn = new HashMap<>();
        int n = 0;
        Iterator<org.bukkit.inventory.Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            org.bukkit.inventory.Recipe r;
            try { r = it.next(); } catch (RuntimeException ex) { continue; }
            if (!(r instanceof FurnaceRecipe fr)) continue;
            String res = skyId(fr.getResult().getType());
            if (res == null) continue;
            Set<String> ins = new LinkedHashSet<>();
            RecipeChoice ch = fr.getInputChoice();
            if (ch instanceof RecipeChoice.MaterialChoice mc) {
                for (Material m : mc.getChoices()) { String id = skyId(m); if (id != null) ins.add(id); }
            } else if (fr.getInput() != null) {
                String id = skyId(fr.getInput().getType());
                if (id != null) ins.add(id);
            }
            if (ins.isEmpty() || ins.contains(res)) continue;
            Smelt s = new Smelt(res, Math.max(1, fr.getResult().getAmount()), List.copyOf(ins), fr.getCookingTime(), fr.getExperience());
            byRes.computeIfAbsent(res, k -> new ArrayList<>()).add(s);
            for (String in : ins) byIn.computeIfAbsent(in, k -> new ArrayList<>()).add(s);
            n++;
        }
        smeltByResult = byRes;
        smeltByInput = byIn;
        return n;
    }

    private String skyId(Material m) {
        if (m == null || m.isAir()) return null;
        try {
            String id = mod.index.canon(Sky.items().vanillaId(m));
            return id != null && Sky.items().exists(id) ? id : null;
        } catch (RuntimeException ex) { return null; }
    }

    // ---- queries ---------------------------------------------------------------------------------------

    /** Registered non-grid recipes making this item (forge, NPC shop, brewing, other stations). */
    List<Recipe> others(String id) {
        List<Recipe> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String look : new LinkedHashSet<>(List.of(id, mod.index.canon(id)))) {
            for (Recipe r : Sky.recipes().producing(look)) {
                if (r == null || r.type == Recipe.Type.CRAFTING || r.type == Recipe.Type.CARPENTRY) continue;
                if (seen.add(r.id == null ? r.type + ":" + r.station + ":" + r.inputs : r.id)) out.add(r);
            }
        }
        out.sort(Comparator.comparing((Recipe r) -> r.type.ordinal()));
        return out;
    }

    private final Map<String, java.util.Optional<Pattern>> forgeCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The first Forge recipe making this item, as a shapeless pattern (coins dropped), so the Raw Materials
     * breakdown can walk through forged intermediates (Refined Mithril -&gt; Enchanted Mithril ...) instead of
     * stopping at them. Null when the item is not forged or its inputs do not fit a 3x3.
     */
    Pattern forgePattern(String rawId) {
        String id = mod.index.canon(rawId);
        return forgeCache.computeIfAbsent(id, k -> {
            for (Recipe r : others(k)) {
                if (r.type != Recipe.Type.FORGE || r.inputs == null || r.inputs.isEmpty() || r.inputs.size() > 9) continue;
                Pattern.Ing[] g = new Pattern.Ing[9];
                int i = 0;
                double coins = 0;
                for (String cell : r.inputs) {
                    CraftMath.Cell c = CraftMath.parse(cell, Sky.items()::exists);
                    if (c == null) continue;
                    if (c.id().equalsIgnoreCase("COINS") || c.id().equalsIgnoreCase("SKYBLOCK_COIN")) { coins += c.amount(); continue; }
                    g[i++] = Pattern.Ing.of(mod.index.canon(c.id()), c.amount());
                }
                if (i == 0) continue;
                try { return java.util.Optional.of(Pattern.fromGrid(r.id, k, Math.max(1, r.amount), true, g, coins, r.unlock, false)); }
                catch (RuntimeException ex) { continue; }
            }
            return java.util.Optional.empty();
        }).orElse(null);
    }

    void clearCaches() { forgeCache.clear(); dropIndex = null; }

    // ---- wave 5: loot rolled in code (slayer bosses, dungeon/Kuudra chests, fishing) ------------------------

    private volatile LootTables.Index loot = new LootTables.Index();

    /** The published loot tables of slayer, dungeons, kuudra and fishing (read once, first tick). Returns item count. */
    int loadLoot() {
        LootTables.Index idx = new LootTables.Index();
        java.util.function.UnaryOperator<String> canon = id -> mod.index.canon(id);
        read("data/slayer/slayers.json", root -> LootTables.slayer(root, canon, idx));
        read("data/dungeons/loot.json", root -> LootTables.dungeons(root, canon, idx));
        String boss = "Magma Leviathan";                     // docs/names/kuudra.md; the mob's own name wins below
        try {
            com.skyclone.api.mob.MobDef d = Sky.mobs().def("leviathan_basic");
            if (d != null && d.name != null && !d.name.isBlank()) boss = Text.strip(d.name);
        } catch (RuntimeException ignored) {}
        String kb = boss;
        read("data/kuudra/loot.json", root -> LootTables.kuudra(root, kb, canon, idx));
        read("data/fishing/fishing.json", root -> LootTables.fishing(root, canon, idx));
        loot = idx;
        int n = 0;
        for (String k : idx.byKind.keySet()) n += idx.items(k);
        return n;
    }

    private static void read(String path, java.util.function.Consumer<com.google.gson.JsonElement> use) {
        try (java.io.InputStream in = Sky.plugin().getResource(path)) {
            if (in == null) return;                                 // that module is not installed
            try (java.io.Reader r = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                use.accept(com.google.gson.JsonParser.parseReader(r));
            }
        } catch (Exception e) {
            Sky.log().warning("[crafting] How to Get could not read " + path + ": " + e);
        }
    }

    List<LootTables.Line> lootLines(String kind, String id) { return loot.get(kind, mod.index.canon(id)); }

    int lootItems(String kind) { return loot.items(kind); }

    /** One mob that drops an item. */
    record Drop(String mobId, String mobName, int level, double chance, int min, int max) {}

    private volatile Map<String, List<Drop>> dropIndex;

    /** Mobs whose loot table holds this item, most likely first (built lazily from every registered MobDef). */
    List<Drop> droppedBy(String rawId) {
        Map<String, List<Drop>> idx = dropIndex;
        if (idx == null) {
            Map<String, List<Drop>> m = new HashMap<>();
            Map<String, Set<String>> seen = new HashMap<>();
            try {
                for (com.skyclone.api.mob.MobDef d : Sky.mobs().all()) {
                    if (d == null || d.drops == null) continue;
                    for (com.skyclone.api.mob.DropDef dr : d.drops) {
                        if (dr == null || dr.item == null) continue;
                        String item = mod.index.canon(dr.item);
                        String name = d.name == null ? d.id : Text.strip(d.name);
                        // one line per mob NAME and item (twenty zombie variants with the same name read as one)
                        if (!seen.computeIfAbsent(item, k -> new java.util.HashSet<>()).add(name)) continue;
                        m.computeIfAbsent(item, k -> new ArrayList<>()).add(new Drop(d.id, name, d.level, dr.chance, dr.min, dr.max));
                    }
                }
            } catch (RuntimeException ignored) {}
            for (List<Drop> l : m.values()) l.sort(Comparator.comparingDouble((Drop x) -> -x.chance()).thenComparingInt(Drop::level));
            dropIndex = idx = m;
        }
        return idx.getOrDefault(mod.index.canon(rawId), List.of());
    }

    /** Registered non-grid recipes that CONSUME this item (what a forge input or a brewing ingredient is for). */
    List<Recipe> othersUsing(String id) {
        List<Recipe> out = new ArrayList<>();
        Set<Recipe> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (String look : new LinkedHashSet<>(List.of(id, mod.index.canon(id)))) {
            for (Recipe r : Sky.recipes().using(look)) {
                if (r == null || r.result == null || r.type == Recipe.Type.CRAFTING || r.type == Recipe.Type.CARPENTRY) continue;
                if (seen.add(r)) out.add(r);
            }
        }
        out.sort(Comparator.comparing((Recipe r) -> r.type.ordinal()).thenComparing(r -> plainName(r.result)));
        return out;
    }

    /** "Station Uses": a page per result made from this item at the Forge, a brewing stand, an NPC... */
    Menu stationUses(Player p, String rawId, int page, Supplier<Menu> back) {
        String id = mod.index.canon(rawId);
        List<Recipe> list = othersUsing(id);
        return Sky.menus().paged("Station Uses: " + Text.strip(CraftMenu.displayName(id)), list,
                r -> {
                    ItemStack it;
                    try { it = Sky.items().create(r.result, Math.max(1, Math.min(64, r.amount))); }
                    catch (RuntimeException ex) { return Icons.of(Material.BARRIER, "&c" + r.result); }
                    long need = 0;
                    for (String cell : r.inputs == null ? List.<String>of() : r.inputs) {
                        CraftMath.Cell c = CraftMath.parse(cell, Sky.items()::exists);
                        if (c != null && mod.index.canon(c.id()).equals(id)) need += c.amount();
                    }
                    List<String> lore = new ArrayList<>();
                    lore.add("");
                    lore.add("&7Made at: &a" + stationName(r));
                    if (need > 0) lore.add("&7Uses: &f" + Text.commas(need) + "x " + CraftEngine.name(id));
                    lore.add("");
                    lore.add("&eClick to see how to get it!");
                    return Icons.withLore(it, lore);
                },
                pc -> menu(pc.click().player(), pc.entry().result, () -> stationUses(pc.click().player(), id, pc.page(), back)).open(pc.click().player()),
                m -> m.back("Previous Menu", back), page);
    }

    static String stationName(Recipe r) {
        if (r.station != null && !r.station.isBlank()) return r.station;
        return switch (r.type) {
            case FORGE -> "the Forge";
            case NPC_SHOP -> "an NPC shop";
            case SMELTING -> "a furnace";
            default -> "a station";
        };
    }

    List<Smelt> smelting(String id) { return smeltByResult.getOrDefault(mod.index.canon(id), List.of()); }

    List<Smelt> smeltsFrom(String id) { return smeltByInput.getOrDefault(mod.index.canon(id), List.of()); }

    int smeltCount() { int n = 0; for (List<Smelt> l : smeltByResult.values()) n += l.size(); return n; }

    /** True when the item has a non-grid recipe: worth a "How to Get" page instead of "Nothing crafts this". */
    boolean has(String id) {
        if (!others(id).isEmpty() || !smelting(id).isEmpty()) return true;
        for (String k : loot.byKind.keySet()) if (!lootLines(k, id).isEmpty()) return true;
        return false;
    }

    /** Items with any non-grid source whose name or id contains the query (for /recipe and Search). */
    List<String> search(String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<String> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        String qid = q.replace(' ', '_');
        Set<String> cands = new LinkedHashSet<>(smeltByResult.keySet());
        for (Recipe r : Sky.recipes().all()) {
            if (r != null && r.result != null && r.type != Recipe.Type.CRAFTING && r.type != Recipe.Type.CARPENTRY) cands.add(r.result);
        }
        for (String id : cands) {
            if (!Sky.items().exists(id)) continue;
            if (plainName(id).contains(q) || id.toLowerCase(Locale.ROOT).contains(qid)) out.add(id);
        }
        out.sort(Comparator.comparing((String id) -> !plainName(id).startsWith(q)).thenComparing(Sources::plainName));
        return out;
    }

    private static String plainName(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? id.toLowerCase(Locale.ROOT) : Text.strip(d.name).toLowerCase(Locale.ROOT);
    }

    // ---- the menu --------------------------------------------------------------------------------------

    Menu menu(Player p, String rawId, Supplier<Menu> back) {
        String id = mod.index.canon(rawId);
        String title = Text.strip(CraftMenu.displayName(id));
        if (title.length() > 20) title = title.substring(0, 20);
        Menu m = new Menu("How to Get: " + title, 6);
        m.chrome();
        Map<String, Integer> inv = p == null ? Map.of() : Stock.inventory(p, mod.index);
        Supplier<Menu> self = () -> menu(p, id, back);

        List<ItemStack> icons = new ArrayList<>();
        List<java.util.function.Consumer<com.skyclone.api.ui.MenuClick>> clicks = new ArrayList<>();

        if (!mod.index.producing(id).isEmpty()) {
            int n = mod.index.producing(id).size();
            List<String> cl = new ArrayList<>();
            cl.add("&7Craft it in a crafting table.");
            if (n > 1) cl.add("&8" + n + " recipes");
            cl.add("");
            cl.add("&eClick to view!");
            icons.add(Icons.of(Material.CRAFTING_TABLE, "&aCrafting Recipe" + (n > 1 ? "s" : ""), cl));
            clicks.add(c -> mod.book.view(c.player(), id, 0, self).open(c.player()));
        }
        for (Recipe r : others(id)) {
            icons.add(recipeIcon(p, r, inv));
            clicks.add(null);
        }
        for (Smelt s : smelting(id)) {
            icons.add(smeltIcon(s, inv));
            String in = s.inputs().get(0);
            clicks.add(c -> {
                if (!mod.index.producing(in).isEmpty() || has(in)) menu(c.player(), in, self).open(c.player());
            });
        }
        List<Drop> drops = droppedBy(id);
        if (!drops.isEmpty()) {
            List<String> lore = new ArrayList<>();
            lore.add("&7Dropped by:");
            int k = 0;
            for (Drop d : drops) {
                if (k++ >= 10) { lore.add(" &8...and " + (drops.size() - 10) + " more"); break; }
                String amt = d.max() > 1 ? (d.min() == d.max() ? d.min() + "x " : d.min() + "-" + d.max() + "x ") : "";
                lore.add(" &8- &7[Lv" + d.level() + "] &c" + d.mobName() + " &8(" + amt + chanceText(d.chance()) + ")");
            }
            icons.add(Icons.of(Material.IRON_SWORD, "&aMob Drop", lore));
            clicks.add(null);
        }
        // Wave 5: loot the owners roll in code, read from their published tables (LootTables).
        lootIcon(icons, clicks, "slayer", id, Material.ROTTEN_FLESH, "&aSlayer Boss Drop", "&7Dropped by:", "slayer");
        lootIcon(icons, clicks, "dungeon", id, Material.CHEST, "&aDungeon Reward Chest", "&7Found in the reward chests of:", null);
        lootIcon(icons, clicks, "kuudra", id, Material.MAGMA_CREAM, "&aBoss Reward Chest", "&7Found in the reward chest of:", null);
        lootIcon(icons, clicks, "fishing", id, Material.FISHING_ROD, "&aFishing", "&7Reeled in while fishing:", "fishing");
        if (Sky.has(Features.Bazaar.class)) {
            try {
                Features.Bazaar bz = Sky.service(Features.Bazaar.class);
                if (bz.isBazaarItem(id)) {
                    icons.add(Icons.of(Material.GOLD_INGOT, "&6Bazaar", "&7Buy and sell it on the Bazaar.", "",
                            "&7Instant buy: &6" + Text.commas(Math.round(bz.instantBuy(id) * 10) / 10.0) + " coins",
                            "&7Instant sell: &6" + Text.commas(Math.round(bz.instantSell(id) * 10) / 10.0) + " coins", "", "&eClick to open!"));
                    clicks.add(c -> { try { Sky.service(Features.Bazaar.class).open(c.player(), id); } catch (RuntimeException ignored) {} });
                }
            } catch (RuntimeException ignored) {}
        }
        if (Sky.has(Features.Auctions.class)) {
            try {
                double bin = Sky.service(Features.Auctions.class).lowestBin(id);
                if (bin > 0) {
                    icons.add(Icons.of(Material.GOLD_BLOCK, "&6Auction House", "&7Other players are selling it.", "",
                            "&7Lowest BIN: &6" + Text.commas(Math.round(bin)) + " coins", "", "&eClick to open!"));
                    clicks.add(c -> { try { Sky.service(Features.Auctions.class).open(c.player()); } catch (RuntimeException ignored) {} });
                }
            } catch (RuntimeException ignored) {}
        }
        if (Sky.has(Features.Collections.class)) {
            try {
                if (Sky.service(Features.Collections.class).isCollectionItem(id)) {
                    icons.add(Icons.of(Material.PAINTING, "&aCollection Item", "&7Gather it out in the world: every one",
                            "&7you pick up counts toward its", "&7collection and unlocks new recipes.", "", "&eClick to view your collections!"));
                    clicks.add(c -> { try { Sky.service(Features.Collections.class).openMenu(c.player()); } catch (RuntimeException ignored) {} });
                }
            } catch (RuntimeException ignored) {}
        }

        ItemStack head;
        try { head = Sky.items().create(id, 1); } catch (RuntimeException ex) { head = Icons.of(Material.BARRIER, "&c" + id); }
        List<String> hl = new ArrayList<>();
        hl.add("");
        hl.add("&7Ways to get it: &a" + icons.size());
        long have = inv.getOrDefault(id, 0);
        if (p != null) hl.add("&7In your inventory: &a" + Text.commas(have));
        m.set(4, Icons.withLore(head, hl));

        if (icons.isEmpty()) {
            m.set(22, Icons.of(Material.BARRIER, "&cNo Known Source", "&7Nothing crafts, forges or sells this.",
                    "&7It drops from something out there."));
        }
        for (int i = 0; i < icons.size() && i < SLOTS.length; i++) {
            var cl = clicks.get(i);
            if (cl == null) m.set(SLOTS[i], icons.get(i)); else m.set(SLOTS[i], icons.get(i), cl);
        }

        int used = mod.index.using(id).size();
        List<Smelt> into = smeltsFrom(id);
        m.set(43, Icons.of(Material.HOPPER, "&aUsed In", "&7Recipes that use this item:", "&a" + used, "", used > 0 ? "&eClick to view!" : "&8Nothing uses it."),
                used > 0 ? c -> mod.book.usedIn(c.player(), id, 0, self).open(c.player()) : null);
        List<Recipe> st = othersUsing(id);
        if (!st.isEmpty()) {
            Map<String, Integer> byStation = new java.util.LinkedHashMap<>();
            for (Recipe r : st) byStation.merge(stationName(r), 1, Integer::sum);
            List<String> lore = new ArrayList<>();
            lore.add("&7Also used outside the crafting table:");
            byStation.forEach((k, v) -> lore.add(" &8- &a" + k + " &7(" + v + ")"));
            lore.add("");
            lore.add("&eClick to view!");
            m.set(42, Icons.of(Material.ANVIL, "&aStation Uses", lore), c -> stationUses(c.player(), id, 0, self).open(c.player()));
        }
        if (!into.isEmpty()) {
            List<String> lore = new ArrayList<>();
            lore.add("&7Put it in a furnace to get:");
            Set<String> res = new LinkedHashSet<>();
            for (Smelt s : into) res.add(s.result());
            int k = 0;
            for (String r : res) { if (k++ >= 6) { lore.add("&8...and " + (res.size() - 6) + " more"); break; } lore.add(" &8- " + CraftEngine.name(r)); }
            String first = res.iterator().next();
            lore.add("");
            lore.add("&eClick to view!");
            m.set(41, Icons.of(Material.FURNACE, "&aSmelts Into", lore), c -> menu(c.player(), first, self).open(c.player()));
        }
        m.set(50, Icons.of(Material.CRAFTING_TABLE, "&aCrafting Table", "&7Open a 3x3 crafting grid.", "", "&eClick to open!"),
                c -> mod.craftMenu.open(c.player()));
        if (back != null) m.back("Previous Menu", back);
        else m.back("Recipe Book", () -> mod.book.root(p));
        return m;
    }

    private void lootIcon(List<ItemStack> icons, List<java.util.function.Consumer<com.skyclone.api.ui.MenuClick>> clicks,
                          String kind, String id, Material icon, String title, String head, String command) {
        List<LootTables.Line> lines = lootLines(kind, id);
        if (lines.isEmpty()) return;
        List<String> lore = new ArrayList<>();
        lore.add(head);
        int k = 0;
        for (LootTables.Line l : lines) {
            if (k++ >= 8) { lore.add(" &8...and " + (lines.size() - 8) + " more"); break; }
            lore.add(" &8- " + l.text());
        }
        boolean canRun = command != null && Sky.commands().exists(command);
        if (canRun) { lore.add(""); lore.add("&eClick to open!"); }
        icons.add(Icons.of(icon, title, lore));
        clicks.add(canRun ? c -> Sky.commands().run(c.player(), command) : null);
    }

    static String chanceText(double c) {
        if (c >= 1) return "always";
        double pct = c * 100;
        if (pct >= 10) return Math.round(pct) + "%";
        if (pct >= 1) return Math.round(pct * 10) / 10.0 + "%";
        if (pct < 0.01) return "<0.01%";
        return Math.round(pct * 100) / 100.0 + "%";
    }

    private ItemStack recipeIcon(Player p, Recipe r, Map<String, Integer> inv) {
        String station = r.station == null || r.station.isBlank() ? null : r.station;
        Material icon;
        String head;
        switch (r.type) {
            case FORGE -> { icon = Material.ANVIL; head = "&aForge"; }
            case NPC_SHOP -> { icon = Material.EMERALD; head = "&aSold by " + (station == null ? "an NPC" : station); }
            case SMELTING -> { icon = Material.FURNACE; head = "&aSmelting"; }
            default -> {
                String st = station == null ? "" : station.toLowerCase(Locale.ROOT);
                icon = st.contains("brew") ? Material.BREWING_STAND : st.contains("anvil") ? Material.ANVIL : Material.PAPER;
                head = "&a" + (station == null ? "Other" : station);
            }
        }
        List<String> lore = new ArrayList<>();
        if (r.type == Recipe.Type.FORGE) lore.add("&7Made at the " + (station == null ? "Forge" : station) + "&7.");
        if (r.amount > 1) lore.add("&7Makes &a" + r.amount + "x&7.");
        List<String> cells = r.inputs == null || r.inputs.isEmpty()
                ? (r.grid == null ? List.of() : java.util.Arrays.stream(r.grid).filter(s -> s != null && !s.isBlank()).toList())
                : r.inputs;
        if (!cells.isEmpty()) {
            lore.add("");
            lore.add(r.type == Recipe.Type.NPC_SHOP ? "&7Cost:" : "&7Needs:");
            Map<String, Long> totals = new java.util.LinkedHashMap<>();
            for (String cell : cells) {
                CraftMath.Cell c = CraftMath.parse(cell, Sky.items()::exists);
                if (c == null) continue;
                String cid = c.id().equalsIgnoreCase("SKYBLOCK_COIN") ? "COINS" : c.id().toUpperCase(Locale.ROOT).equals("COINS") ? "COINS" : mod.index.canon(c.id());
                totals.merge(cid, (long) c.amount(), Long::sum);
            }
            int k = 0;
            for (Map.Entry<String, Long> e : totals.entrySet()) {
                if (k++ >= 10) { lore.add(" &8...and " + (totals.size() - 10) + " more"); break; }
                if (e.getKey().equals("COINS")) { lore.add(" &6" + Text.commas(e.getValue()) + " Coins"); continue; }
                long have = inv.getOrDefault(e.getKey(), 0);
                String mark = p == null ? "" : have >= e.getValue() ? " &a✔" : " &c✖ &8(" + Text.commas(have) + ")";
                lore.add(" &f" + Text.commas(e.getValue()) + "x " + CraftEngine.name(e.getKey()) + mark);
            }
        }
        if (r.type == Recipe.Type.FORGE && r.durationSeconds > 0) {
            lore.add("");
            lore.add("&7Forge time: &a" + Text.duration(r.durationSeconds));
        }
        if (r.unlock != null && !r.unlock.isBlank()) {
            String desc;
            desc = CraftEngine.describe(r.unlock);
            boolean ok = false;
            if (p != null) { try { ok = Sky.recipes().unlocked(p, r); } catch (RuntimeException ignored) {} }
            lore.add("");
            lore.add("&7Requires " + desc + (p == null ? "" : ok ? " &a✔" : " &c✖"));
        }
        if (r.type == Recipe.Type.FORGE) { lore.add(""); lore.add("&8Start it at the Forge in the Dwarven Mines."); }
        else if (r.type == Recipe.Type.NPC_SHOP && station != null) { lore.add(""); lore.add("&8Visit the " + station + " to buy it."); }
        return Icons.of(icon, head, lore);
    }

    private ItemStack smeltIcon(Smelt s, Map<String, Integer> inv) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Smelt it in a furnace on your island.");
        lore.add("");
        lore.add(s.inputs().size() > 1 ? "&7Put any of these in:" : "&7Put this in:");
        int k = 0;
        for (String in : s.inputs()) {
            if (k++ >= 6) { lore.add(" &8...and " + (s.inputs().size() - 6) + " more"); break; }
            long have = inv.getOrDefault(in, 0);
            lore.add(" &f" + CraftEngine.name(in) + (have > 0 ? " &a✔ &8(" + Text.commas(have) + ")" : ""));
        }
        lore.add("");
        lore.add("&7Makes &a" + s.amount() + "x &7per item, &a" + Text.commas(Math.round(s.cookTicks() / 2.0) / 10.0) + "s &7each.");
        String in = s.inputs().get(0);
        if (!mod.index.producing(in).isEmpty() || has(in)) { lore.add(""); lore.add("&eClick to see how to get " + Text.strip(CraftMenu.displayName(in)) + "!"); }
        return Icons.of(Material.FURNACE, "&aFurnace", lore);
    }
}
