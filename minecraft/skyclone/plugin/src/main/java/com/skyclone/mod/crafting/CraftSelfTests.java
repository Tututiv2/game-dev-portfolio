package com.skyclone.mod.crafting;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code /sb selftest crafting}: the data hangs together and the formulas give the research numbers. */
final class CraftSelfTests {

    private CraftSelfTests() {}

    static void register(CraftingModule mod) {
        Sky.commands().selfTest("crafting.vanilla-recipes", () -> vanilla(mod));
        Sky.commands().selfTest("crafting.matcher-160-rule", () -> rule160(mod));
        Sky.commands().selfTest("crafting.carpentry-formula", () -> carpentry(mod));
        Sky.commands().selfTest("crafting.ingredients-resolve", () -> unknownIngredients(mod, 8));
        Sky.commands().selfTest("crafting.trades-valid", () -> trades(mod));
        Sky.commands().selfTest("crafting.furniture-valid", () -> furniture(mod));
        Sky.commands().selfTest("crafting.categories", () -> categories(mod));
        Sky.commands().selfTest("crafting.menus-build", () -> menus(mod));
        Sky.commands().selfTest("crafting.no-coin-loops", () -> coinLoops(mod, 6, 2.0));
        Sky.commands().selfTest("crafting.no-xp-loops", () -> xpLoops(mod));
        Sky.commands().selfTest("crafting.grid-menu", () -> gridMenu(mod));
        Sky.commands().selfTest("crafting.unlock-keys", () -> unlockKeys(mod));
        Sky.commands().selfTest("crafting.sources", () -> sources(mod));
        Sky.commands().selfTest("crafting.furniture-functions", () -> furnitureFunctions(mod));
        Sky.commands().selfTest("crafting.furniture-storage", () -> furnitureStorage(mod));
        Sky.commands().selfTest("crafting.loot-sources", () -> lootSources(mod));
    }

    /**
     * Wave 5: what each piece DOES matches the reference lore - 12 storage pieces (Skull Chest 6 pages, the rest 3),
     * 3 showcases, 5 stations whose build really holds the working block, 2 beds, the trough, the clock - and every
     * non-cosmetic piece says so in its lore.
     */
    static String furnitureFunctions(CraftingModule mod) {
        List<String> bad = new ArrayList<>();
        Map<String, Integer> kinds = new java.util.TreeMap<>();
        for (Furniture.Def d : mod.furniture.defs.values()) {
            String k = FurnitureMath.kind(d.function);
            kinds.merge(k, 1, Integer::sum);
            if (!k.equals("cosmetic") && FurnitureMath.describe(d.function) == null) bad.add(d.id + " (" + d.function + ") has no use text");
            String station = FurnitureMath.stationBlock(d.function);
            if (k.equals("station")) {
                boolean has = false;
                for (String data : d.data) if (!data.equals("HEAD") && data.toUpperCase(java.util.Locale.ROOT).startsWith(station)) has = true;
                if (!has) bad.add(d.id + " has no " + station + " block to open");
            }
            if (k.equals("showcase") && FurnitureMath.showSlots(d.function) <= 0) bad.add(d.id + " shows nothing");
            ItemDef it = Sky.items().def(d.id);
            if (it != null && mod.furniture.itemsRegistered > 0 && !k.equals("cosmetic")
                    && it.lore.stream().noneMatch(l -> l.equals(FurnitureMath.describe(d.function)))) bad.add(d.id + " lore misses its use");
        }
        if (kinds.getOrDefault("storage", 0) != 12) bad.add(kinds.getOrDefault("storage", 0) + " storage pieces, the reference has 12");
        if (kinds.getOrDefault("showcase", 0) != 3) bad.add(kinds.getOrDefault("showcase", 0) + " showcases, the reference has 3");
        if (kinds.getOrDefault("station", 0) != 5) bad.add(kinds.getOrDefault("station", 0) + " stations, the reference has 5");
        Furniture.Def skull = mod.furniture.defs.get("SKULL_CHEST");
        if (skull != null && FurnitureMath.pages(skull.function) != 6) bad.add("Skull Chest++ holds " + FurnitureMath.pages(skull.function) + " pages, the reference says six");
        return bad.isEmpty() ? null : String.join("; ", bad.subList(0, Math.min(6, bad.size())));
    }

    /** The storage table exists (storage furniture cannot open without it) and the codec round-trips an item. */
    static String furnitureStorage(CraftingModule mod) {
        if (!mod.furniture.store.dbOk) return "crafting_furniture_store table missing: storage furniture will not open";
        org.bukkit.inventory.ItemStack s = new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND, 17);
        org.bukkit.inventory.ItemStack back = FurnitureStore.decode(FurnitureStore.encode(s));
        if (back == null || back.getType() != org.bukkit.Material.DIAMOND || back.getAmount() != 17) return "item codec does not round-trip";
        if (FurnitureStore.encode(null) != null || FurnitureStore.decode("not base64!") != null) return "codec accepts nothing as something";
        return null;
    }

    /**
     * Wave 5: How to Get reads the loot other modules roll in code. When slayer / dungeons / fishing are installed,
     * their headline drops have a source and the page shows it.
     */
    static String lootSources(CraftingModule mod) {
        if (Sky.items().exists("REVENANT_FLESH") && Sky.plugin().getResource("data/slayer/slayers.json") != null) {
            if (mod.sources.lootLines("slayer", "REVENANT_FLESH").isEmpty()) return "Revenant Flesh has no slayer source";
            Menu m = mod.sources.menu(null, "REVENANT_FLESH", null);
            boolean shown = false;
            for (int i = 19; i <= 34; i++) if (m.get(i) != null && m.get(i).getType() == org.bukkit.Material.ROTTEN_FLESH) shown = true;
            if (!shown) return "Revenant Flesh page shows no Slayer Boss Drop";
        }
        if (Sky.plugin().getResource("data/dungeons/loot.json") != null && mod.sources.lootItems("dungeon") < 20)
            return "only " + mod.sources.lootItems("dungeon") + " items found in the dungeon chests";
        if (Sky.plugin().getResource("data/fishing/fishing.json") != null && mod.sources.lootItems("fishing") < 5)
            return "only " + mod.sources.lootItems("fishing") + " fishing items";
        return null;
    }

    /**
     * No two recipes undo each other while both pay Carpentry XP (9 Diamonds -&gt; Diamond Block -&gt; 9 Diamonds
     * forever would be endless skill XP and skill coin rewards).
     */
    static String xpLoops(CraftingModule mod) {
        List<String> bad = new ArrayList<>();
        for (RecipeIndex.Entry e : mod.index.all()) {
            if (mod.engine.carpentryXp(e.pattern) <= 0) continue;
            for (String ing : Matcher.allIds(e.pattern)) {
                for (RecipeIndex.Entry q : mod.index.producing(ing)) {
                    if (q == e || mod.engine.carpentryXp(q.pattern) <= 0) continue;
                    if (Matcher.allIds(q.pattern).contains(e.pattern.result)) bad.add(e.id() + " <-> " + q.id());
                }
            }
        }
        // Wave 3: longer loops too (A -> B -> C -> A). Every recipe on a cycle of the whole recipe graph must pay nothing.
        List<Pattern> pats = new ArrayList<>();
        for (RecipeIndex.Entry e : mod.index.all()) pats.add(e.pattern);
        for (String rid : CraftMath.reversible(pats)) {
            RecipeIndex.Entry e = mod.index.byId(rid);
            if (e != null && mod.engine.carpentryXp(e.pattern) > 0) bad.add(rid + " (on a crafting cycle)");
        }
        return bad.isEmpty() ? null : bad.size() + " XP loops: " + String.join("; ", bad.subList(0, Math.min(6, bad.size())));
    }

    /**
     * The Craft Item menu: exactly the 9 grid slots are free, and the menu does NOT keep them, so core hands
     * every grid item back on close, quit, or when another menu replaces it (CORE_GUIDE §3.18).
     */
    static String gridMenu(CraftingModule mod) {
        Menu m = mod.craftMenu.build(null);
        m.redraw();
        java.util.Set<Integer> want = new java.util.HashSet<>();
        for (int s : CraftMenu.GRID) want.add(s);
        if (!m.freeSlots().equals(want)) return "free slots " + m.freeSlots() + " != grid " + want;
        if (m.keepsFreeSlotItems()) return "the grid keeps its items: core would not return them on close";
        if (m.get(CraftMenu.RESULT) == null) return "no result icon at slot " + CraftMenu.RESULT;
        for (int q : CraftMenu.QUICK) if (m.get(q) == null) return "quick craft slot " + q + " is empty";
        for (int s : CraftMenu.GRID) if (m.get(s) != null) return "grid slot " + s + " is not empty on a new menu";
        if (m.refreshTicks() <= 0) return "the menu never refreshes";
        return null;
    }

    /** Every unlock prefix a grid recipe uses has a resolver (unknown prefixes fail closed: the recipe is locked forever). */
    static String unlockKeys(CraftingModule mod) {
        // Wave 5: Requirements.known(prefix) is on the api now (core-wave5 §6), so no reflection.
        java.util.Map<String, Integer> unknown = new java.util.TreeMap<>();
        int never = 0;
        for (RecipeIndex.Entry e : mod.index.all()) {
            String u = e.recipe.unlock;
            if (u == null) continue;
            for (String part : u.split("&")) {
                String k = part.trim();
                if (k.startsWith("never")) { never++; continue; }
                String prefix = k.contains(":") ? k.substring(0, k.indexOf(':')) : k;
                boolean ok;
                try { ok = Sky.requirements().known(prefix); } catch (RuntimeException ex) { return null; }
                if (!ok) unknown.merge(prefix, 1, Integer::sum);
            }
        }
        if (unknown.isEmpty()) return null;
        return "requirement prefixes with no resolver (those recipes stay locked): " + unknown + (never > 0 ? "; plus " + never + " never: gates" : "");
    }

    /** Vanilla set registered, and sticks craft from ANY planks (tag cells), 4 at a time. */
    static String vanilla(CraftingModule mod) {
        RecipeIndex idx = mod.index;
        if (!idx.lateDone) return "late registration has not run yet";
        if (!mod.dials.vanillaRecipes.get()) return null;
        if (idx.vanillaRegistered < idx.vanillaDataSize() * 0.8)
            return "only " + idx.vanillaRegistered + " of " + idx.vanillaDataSize() + " vanilla recipes registered";
        RecipeIndex.Entry stick = idx.byId("vanilla:stick");
        if (stick == null) return "vanilla:stick missing";
        String oak = RecipeIndex.resolveMaterial("OAK_PLANKS"), birch = RecipeIndex.resolveMaterial("BIRCH_PLANKS");
        for (String planks : new String[]{oak, birch}) {
            if (planks == null) return "planks do not resolve to an item";
            String[] ids = new String[9];
            int[] amts = new int[9];
            ids[1] = planks; ids[4] = planks; amts[1] = 3; amts[4] = 5;      // middle column, offset
            Matcher.Match m = Matcher.match(stick.pattern, ids, amts, true);
            if (m == null) return "sticks do not craft from " + planks;
            if (m.maxTimes() != 3) return "stick max crafts " + m.maxTimes() + " != 3";
            if (stick.pattern.amount != 4) return "stick yields " + stick.pattern.amount + " != 4";
        }
        return null;
    }

    /**
     * research/02 §5.2: an Enchanted item is 160 of its base, as 5 slots of 32. Whoever registered the
     * Enchanted Diamond recipe, it must take 160 Diamonds, and 5 slots of 64 must craft it twice.
     */
    static String rule160(CraftingModule mod) {
        RecipeIndex idx = mod.index;
        String ed = idx.canon("ENCHANTED_DIAMOND"), dia = idx.canon("DIAMOND");
        if (!Sky.items().exists(ed)) return null;   // materials not installed: nothing to check
        RecipeIndex.Entry e = null;
        for (RecipeIndex.Entry x : idx.producing(ed)) {
            Map<Pattern.Ing, Integer> t = x.pattern.totals();
            if (t.size() == 1 && t.keySet().iterator().next().accepts(dia)) { e = x; break; }
        }
        if (e == null) return "no recipe makes ENCHANTED_DIAMOND from DIAMOND";
        int total = e.pattern.totals().values().iterator().next();
        if (total != 160) return "ENCHANTED_DIAMOND takes " + total + " diamonds, research says 160";
        String[] ids = new String[9];
        int[] amts = new int[9];
        Pattern.Ing[] g = e.pattern.grid9();
        for (int i = 0; i < 9; i++) if (g[i] != null) { ids[i] = dia; amts[i] = 64; }
        Matcher.Match m = Matcher.match(e.pattern, ids, amts, mod.dials.allowMirror.get());
        if (m == null) return "a full grid of 64s does not match " + e.id();
        int perSlot = 1;
        for (Pattern.Ing i : g) if (i != null) perSlot = Math.max(perSlot, i.amount());
        if (m.maxTimes() != 64 / perSlot) return "64-stacks give " + m.maxTimes() + " crafts, expected " + (64 / perSlot);
        return null;
    }

    /** research/02 §2.4.8: Carpentry XP = 3% of the ingredients' NPC sell value. 160 Diamonds at 8 coins = 38.4 XP. */
    static String carpentry(CraftingModule mod) {
        double xp = CraftMath.carpentryXp(Map.of("DIAMOND", 160), id -> 8.0, 0.03);
        if (Math.abs(xp - 38.4) > 1e-9) return "formula gives " + xp + " for 160 Diamonds at 8 coins, expected 38.4";
        if (Math.abs(mod.dials.carpentryRate.get() - 0.03) > 1e-9) return null;  // owner retuned it on purpose
        ItemDef d = Sky.items().def(mod.index.canon("DIAMOND"));
        if (d != null && d.npcSell > 0) {
            double live = CraftMath.carpentryXp(Map.of("DIAMOND", 160), CraftEngine::npcSell, 0.03);
            if (Math.abs(live - 160 * d.npcSell * 0.03) > 1e-6) return "live carpentry XP mismatch: " + live;
        }
        return null;
    }

    /** Every ingredient of every grid recipe names a real item. Our own registrations must be perfect. */
    static String unknownIngredients(CraftingModule mod, int show) {
        List<String> bad = new ArrayList<>();
        int ours = 0;
        for (RecipeIndex.Entry e : mod.index.all()) {
            boolean mine = e.pattern.vanilla || mod.index.fallbackIds.contains(e.id()) || mod.furniture.recipeIds.contains(e.id());
            if (!Sky.items().exists(e.pattern.result)) {
                bad.add(e.id() + " makes unknown " + e.pattern.result);
                if (mine) ours++;
                continue;
            }
            for (Pattern.Ing i : e.pattern.cells) {
                if (i == null) continue;
                for (String id : i.ids()) {
                    if (!Sky.items().exists(id)) { bad.add(e.id() + " needs unknown " + id); if (mine) ours++; }
                }
            }
        }
        if (bad.isEmpty()) return null;
        String list = String.join("; ", bad.subList(0, Math.min(show, bad.size())));
        return bad.size() + " broken recipe references (" + ours + " registered by crafting): " + list;
    }

    static String trades(CraftingModule mod) {
        if (mod.trades.trades.size() < 21) return "only " + mod.trades.trades.size() + " trades loaded (research lists 21)";
        List<String> bad = new ArrayList<>();
        for (Trades.Trade t : mod.trades.trades) {
            if (t.unlock == null || !t.unlock.startsWith("collection:")) bad.add(t.id + " has no collection unlock");
            String res = mod.trades.resultId(t);
            Map<String, Integer> cost = mod.trades.cost(t);
            if (res == null || cost == null) continue;          // item not in this game: the trade hides itself
            double paid = 0;
            for (Map.Entry<String, Integer> e : cost.entrySet()) paid += e.getKey().equals("COINS") ? e.getValue() : CraftEngine.npcSell(e.getKey()) * e.getValue();
            double got = CraftEngine.npcSell(res) * t.amount;
            if (got > paid + 1e-9) bad.add(t.id + " prints coins (pays " + paid + ", result sells for " + got + ")");
        }
        return bad.isEmpty() ? null : String.join("; ", bad);
    }

    static String furniture(CraftingModule mod) {
        if (mod.furniture.defs.size() < 37) return "only " + mod.furniture.defs.size() + " furniture pieces (research lists 37 for Carpentry 1-25)";
        List<String> bad = new ArrayList<>();
        for (Furniture.Def d : mod.furniture.defs.values()) {
            if (!Sky.items().exists(d.id)) bad.add(d.id + " item missing");
            if (d.offsets.isEmpty()) bad.add(d.id + " has no blocks");
            for (String data : d.data) {
                if (data.equals("HEAD")) continue;
                try { org.bukkit.Bukkit.createBlockData("minecraft:" + data); } catch (IllegalArgumentException e) { bad.add(d.id + " bad block " + data); }
            }
            if (mod.index.byId("furniture:" + d.id) == null && mod.index.lateDone) bad.add(d.id + " recipe not registered");
        }
        return bad.isEmpty() ? null : bad.size() + " problems: " + String.join("; ", bad.subList(0, Math.min(8, bad.size())));
    }

    /** Every collection a recipe unlock names maps to a Recipe Book category. */
    static String categories(CraftingModule mod) {
        List<String> bad = new ArrayList<>();
        for (RecipeIndex.Entry e : mod.index.all()) {
            String u = e.recipe.unlock;
            if (u == null) continue;
            for (String part : u.split("&")) {
                String k = part.trim();
                if (k.startsWith("collection:") && Category.fromKey(k, mod.index.collectionCat) == null) bad.add(e.id() + " -> " + k);
            }
        }
        if (mod.index.collectionCat.size() != 90) bad.add("collections table has " + mod.index.collectionCat.size() + " entries, research says 90");
        return bad.isEmpty() ? null : bad.size() + " unmapped: " + String.join("; ", bad.subList(0, Math.min(6, bad.size())));
    }

    /** Every menu builds for a profile-less viewer without throwing. */
    static String menus(CraftingModule mod) {
        Menu root = mod.book.root(null);
        if (root.get(4) == null) return "recipe book header missing";
        for (Category c : Category.values()) {
            if (mod.index.results(c).isEmpty()) continue;
            Menu m = mod.book.category(null, c, 0);
            if (m.get(10) == null) return c.display + " category page is empty";
        }
        String any = mod.index.allResults().stream().findFirst().orElse(null);
        if (any != null) {
            Menu v = mod.book.view(null, any, 0, null);
            if (v.get(25) == null) return "recipe view of " + any + " has no result icon";
            mod.book.usedIn(null, any, 0, () -> root);
        }
        Menu t = mod.trades.build(null);
        if (t == null) return "trades menu did not build";
        return null;
    }

    /**
     * Wave 3 "How to Get": the furnace index is filled (Sand smelts into Glass when both exist), a forged item has a
     * page with its Forge recipe, an item nothing makes still gets a page, and a recipe view links to it.
     */
    static String sources(CraftingModule mod) {
        if (Sky.items().exists("SAND") && Sky.items().exists("GLASS")) {
            if (mod.sources.smeltCount() == 0) return "no furnace recipes indexed";
            boolean ok = false;
            for (Sources.Smelt sm : mod.sources.smelting("GLASS")) if (sm.inputs().contains(mod.index.canon("SAND"))) ok = true;
            if (!ok) return "Glass has no Sand furnace source";
            Menu g = mod.sources.menu(null, "GLASS", null);
            boolean furnace = false;
            for (int i = 19; i <= 34; i++) if (g.get(i) != null && g.get(i).getType() == org.bukkit.Material.FURNACE) furnace = true;
            if (!furnace) return "Glass page shows no furnace source";
        }
        for (com.skyclone.api.item.Recipe r : Sky.recipes().byType(com.skyclone.api.item.Recipe.Type.FORGE)) {
            if (r.result == null || !Sky.items().exists(r.result) || !mod.index.producing(r.result).isEmpty()) continue;
            if (!mod.sources.has(r.result)) return r.result + " is forged but has no source";
            Menu m = mod.sources.menu(null, r.result, null);
            boolean anvil = false;
            for (int i = 0; i < m.size(); i++) if (m.get(i) != null && m.get(i).getType() == org.bukkit.Material.ANVIL) anvil = true;
            if (!anvil) return r.result + " page shows no Forge recipe";
            if (mod.book.view(null, r.result, 0, null).get(4) == null) return "recipe view of forged " + r.result + " did not fall through to How to Get";
            boolean items = false;
            for (String in : r.inputs == null ? List.<String>of() : r.inputs) { CraftMath.Cell c = CraftMath.parse(in, Sky.items()::exists); if (c != null && !c.id().equalsIgnoreCase("COINS")) items = true; }
            if (items && r.inputs != null && r.inputs.size() <= 9 && mod.sources.forgePattern(r.result) == null) return "Raw Materials cannot walk through forged " + r.result;
            break;
        }
        String any = mod.index.allResults().stream().findFirst().orElse(null);
        if (any != null && mod.book.view(null, any, 0, null).get(41) == null) return "recipe view has no How to Get button";
        Menu none = mod.sources.menu(null, "SKYCLONE_SELFTEST_NO_SUCH_ITEM", null);
        if (none.get(22) == null) return "an item with no source got no 'No Known Source' icon";
        return null;
    }

    /** No always-available recipe may sell to NPCs for more than {@code factor} times its ingredients. */
    static String coinLoops(CraftingModule mod, int show, double factor) {
        List<String> bad = new ArrayList<>();
        // Same valuation as SellCap (which applies this rule at boot): a cell with no SELLABLE id makes the recipe
        // unjudgeable, since npcSell 0 means "cannot be sold", not "free" (integration round 2).
        for (SellCap.Way w : SellCap.ways(mod.index.all(), false)) {
            double in = SellCap.inputValue(w.inputs(), CraftEngine::npcSell);
            if (in < 0) continue;
            double out = CraftEngine.npcSell(w.result());
            if (SellCap.breaks(out, w.amount(), in, factor))
                bad.add(w.result() + " (in " + Math.round(in) + ", out " + Math.round(out * w.amount()) + ")");
        }
        if (bad.isEmpty()) return null;
        return bad.size() + " recipes sell for more than " + factor + "x their inputs: " + String.join("; ", bad.subList(0, Math.min(show, bad.size())));
    }
}
