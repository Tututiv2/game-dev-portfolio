package com.skyclone.mod.crafting;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /sb crafting ...} — every admin action prints synchronously so it works over RCON, and
 * every one that acts on a player takes the player's name.
 */
final class CraftAdmin {

    private static final List<String> SUBS = List.of("info", "match", "recipes", "craft", "ingredients", "open", "book",
            "trades", "trade", "tradereset", "bypass", "tree", "furniture", "check", "rebuild", "sellcap", "sources");

    private final CraftingModule mod;

    CraftAdmin(CraftingModule mod) { this.mod = mod; }

    void register() {
        Sky.commands().admin("crafting", "<" + String.join("|", SUBS) + "> ...",
                "Crafting tools: test the matcher, craft for a player, inspect recipes, trades and furniture",
                this::run, (s, a) -> complete(a));
    }

    private List<String> complete(String[] a) {
        if (a.length <= 1) return filter(SUBS, a.length == 0 ? "" : a[0]);
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2 && List.of("craft", "ingredients", "open", "book", "trades", "trade", "tradereset", "bypass").contains(sub)) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, a[1]);
        }
        if ((a.length == 2 && (sub.equals("recipes") || sub.equals("tree") || sub.equals("sources"))) || (a.length == 3 && (sub.equals("craft") || sub.equals("ingredients")))) {
            return mod.completeResults(a[a.length - 1]);
        }
        if (sub.equals("furniture") && a.length == 2) return filter(List.of("list", "give", "place"), a[1]);
        return List.of();
    }

    private static List<String> filter(List<String> l, String pre) {
        List<String> out = new ArrayList<>();
        for (String s : l) if (s.toLowerCase(Locale.ROOT).startsWith(pre.toLowerCase(Locale.ROOT))) out.add(s);
        return out;
    }

    private static void say(CommandSender s, String legacy) { s.sendMessage(Text.c(legacy)); }

    private void run(CommandSender s, String[] a) {
        if (a.length == 0) { usage(s); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(s);
            case "sellcap" -> {
                boolean dry = a.length > 1 && a[1].equalsIgnoreCase("dry");
                Map<String, String> ch = mod.applySellCap(dry);
                say(s, (dry ? "&eWould lower " : "&aLowered ") + ch.size() + " NPC sell prices (crafting.sell-cap-factor "
                        + mod.dials.sellCapFactor.get() + ")");
                for (Map.Entry<String, String> e : ch.entrySet()) say(s, "&7 " + e.getKey() + " &f" + e.getValue());
            }
            case "match" -> match(s, a);
            case "recipes" -> recipes(s, a);
            case "sources" -> sources(s, a);
            case "craft" -> craft(s, a);
            case "ingredients" -> ingredients(s, a);
            case "open" -> { Player p = player(s, a, 1); if (p != null) { mod.craftMenu.open(p); say(s, "&aOpened Craft Item for " + p.getName()); } }
            case "book" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                if (a.length > 2) mod.book.openFor(p, String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)));
                else mod.book.openRoot(p);
                say(s, "&aOpened the Recipe Book for " + p.getName());
            }
            case "trades" -> { Player p = player(s, a, 1); if (p != null) { mod.trades.open(p); say(s, "&aOpened Trades for " + p.getName()); } }
            case "trade" -> trade(s, a);
            case "tradereset" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                CraftData d = mod.engine.data(p);
                if (d != null) { d.tradedToday.clear(); d.tradeDay = ""; }
                say(s, "&aReset today's trade counters for " + p.getName());
            }
            case "bypass" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                boolean on = a.length > 2 ? a[2].equalsIgnoreCase("on") : !mod.engine.bypass.contains(p.getUniqueId());
                if (on) mod.engine.bypass.add(p.getUniqueId()); else mod.engine.bypass.remove(p.getUniqueId());
                say(s, "&aRecipe unlock bypass for " + p.getName() + ": " + (on ? "&aON" : "&cOFF") + " &7(until they log out)");
            }
            case "tree" -> tree(s, a);
            case "furniture" -> furniture(s, a);
            case "check" -> check(s);
            case "rebuild" -> { mod.index.ensure(); mod.sources.clearCaches(); say(s, "&aIndex: " + mod.index.all().size() + " grid recipes"); }
            default -> usage(s);
        }
    }

    private void usage(CommandSender s) {
        say(s, "&e/sb crafting info &7- counts and boot registration report");
        say(s, "&e/sb crafting match <cell x9> &7- run the grid matcher, cells like DIAMOND:32 or - for empty");
        say(s, "&e/sb crafting recipes <item> &7- every recipe making an item, with unlocks");
        say(s, "&e/sb crafting sources <item> &7- the How to Get page as text: forge, shop, station and furnace recipes");
        say(s, "&e/sb crafting craft <player> <item|recipeId> [times] &7- supercraft for a player from their inventory");
        say(s, "&e/sb crafting ingredients <player> <item> [times] &7- give the ingredients of an item's first recipe");
        say(s, "&e/sb crafting open|book|trades <player> [item] &7- open a crafting menu for a player");
        say(s, "&e/sb crafting trade <player> <tradeId> [times] &7- make a trade for a player");
        say(s, "&e/sb crafting tradereset <player> &7- reset today's trade counters");
        say(s, "&e/sb crafting bypass <player> [on|off] &7- ignore recipe unlocks for a player (until they log out)");
        say(s, "&e/sb crafting tree <item> [amount] &7- raw-material breakdown");
        say(s, "&e/sb crafting furniture list | give <player> <id> [n] | place <player> <id> | storage | orphans <player> &7- furniture tools (place = on the block they look at)");
        say(s, "&e/sb crafting check &7- data report: unknown ingredients, free coin loops");
    }

    private Player player(CommandSender s, String[] a, int i) {
        if (a.length <= i) { say(s, "&cName a player."); return null; }
        Player p = Bukkit.getPlayerExact(a[i]);
        if (p == null) { say(s, "&cNo online player " + a[i]); return null; }
        if (!Sky.profiles().isReady(p)) { say(s, "&c" + p.getName() + "'s profile is not ready yet."); return null; }
        return p;
    }

    private void info(CommandSender s) {
        RecipeIndex idx = mod.index;
        say(s, "&6&lCrafting");
        say(s, "&7Grid recipes indexed: &a" + idx.all().size() + " &7(results: &a" + idx.allResults().size() + "&7)");
        Map<Category, Integer> counts = idx.recipeCounts();
        StringBuilder sb = new StringBuilder("&7By category:");
        counts.forEach((c, n) -> sb.append(" ").append(c.color).append(c.display).append("&7=").append(n));
        say(s, sb.toString());
        say(s, "&7Late registration done: " + (idx.lateDone ? "&ayes" : "&cno (runs on the first tick)"));
        say(s, "&7Vanilla: &a" + idx.vanillaRegistered + " &7registered, &c" + idx.vanillaSkipped + " &7skipped of " + idx.vanillaDataSize());
        say(s, "&7Reference fill-in: &a" + idx.fallbackRegistered + " &7registered, " + idx.fallbackOwned + " owned by modules, "
                + idx.fallbackMissingResult + " result missing, " + idx.fallbackMissingIngredient + " ingredient missing (of " + idx.fallbackDataSize() + ")");
        if (!idx.fallbackMissingSamples.isEmpty()) say(s, "&8e.g. " + String.join("; ", idx.fallbackMissingSamples));
        say(s, "&7Ambiguous cell texts (core index only): " + idx.ambiguousCells);
        say(s, "&7Reversible recipes (no Carpentry XP, would loop): &e" + idx.reversibleCount());
        say(s, "&7Chunks holding furniture (loaded): &a" + mod.furniture.markedChunks());
        say(s, "&7Furniture: &a" + mod.furniture.defs.size() + " &7defs, " + mod.furniture.itemsRegistered + " items registered here, "
                + mod.furniture.recipesRegistered + " recipes (" + mod.furniture.recipesSkipped + " skipped)");
        int avail = 0;
        for (Trades.Trade t : mod.trades.trades) if (mod.trades.resultId(t) != null && mod.trades.cost(t) != null) avail++;
        say(s, "&7Trades: &a" + avail + "&7/" + mod.trades.trades.size() + " available, daily limit &a" + mod.trades.limit()
                + (mod.trades.spree() ? " &6(Shopping Spree)" : ""));
    }

    private void match(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&cGive up to 9 cells: ITEM:amount or - for empty (row by row)."); return; }
        String[] ids = new String[9];
        int[] amts = new int[9];
        for (int i = 0; i < 9 && i + 1 < a.length; i++) {
            String c = a[i + 1];
            if (c.equals("-") || c.equals("_")) continue;
            CraftMath.Cell cell = CraftMath.parse(c, Sky.items()::exists);
            ids[i] = mod.index.canon(cell.id());
            amts[i] = cell.amount();
        }
        Player viewer = s instanceof Player p ? p : null;
        CraftEngine.Found f = mod.engine.find(viewer, new CraftEngine.Grid(ids, amts, new org.bukkit.inventory.ItemStack[9]));
        if (f == null) { say(s, "&cNo recipe matches that grid."); return; }
        Pattern pat = f.entry().pattern;
        say(s, "&aMatch: &f" + f.entry().id() + " &7-> &f" + pat.amount + "x " + pat.result + " &7(" + f.entry().category.display + ")");
        if (!f.enough()) say(s, "&6Shape matches but some slots hold too few items.");
        else say(s, "&7Crafts the grid holds: &a" + f.match().maxTimes() + " &7| consume per craft: " + java.util.Arrays.toString(f.match().consume()));
        say(s, "&7Unlock: " + (f.entry().recipe.unlock == null ? "&anone" : f.entry().recipe.unlock) + (viewer != null ? " &7(you: " + (f.unlocked() ? "&aunlocked" : "&clocked") + "&7)" : ""));
        String why = mod.engine.noXpReason(pat);
        say(s, "&7Carpentry XP per craft: &3" + Text.commas(Math.round(mod.engine.carpentryXp(pat) * 100) / 100.0)
                + (why == null ? "" : " &8(" + why + ")"));
    }

    private void recipes(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&cName an item id."); return; }
        String id = a[1].toUpperCase(Locale.ROOT);
        List<RecipeIndex.Entry> l = mod.index.producing(id);
        say(s, "&6" + id + " &7(canonical " + mod.index.canon(id) + ")&7: &a" + l.size() + " &7recipes, used in &a" + mod.index.using(id).size());
        for (RecipeIndex.Entry e : l) {
            Pattern p = e.pattern;
            StringBuilder g = new StringBuilder();
            Pattern.Ing[] g9 = p.grid9();
            for (int i = 0; i < 9; i++) {
                if (i % 3 == 0) g.append(" &8|");
                g.append(" ").append(g9[i] == null ? "&8-" : "&f" + g9[i].display() + "&7x" + g9[i].amount() + (g9[i].ids().size() > 1 ? "&8(+" + (g9[i].ids().size() - 1) + ")" : ""));
            }
            say(s, "&e" + e.id() + " &7" + (p.shapeless ? "shapeless" : p.w + "x" + p.h) + " -> " + p.amount + " &7| unlock " + (e.recipe.unlock == null ? "&anone" : "&f" + e.recipe.unlock) + " &7| " + e.category.display);
            say(s, g.toString());
        }
    }

    private void sources(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&7Furnace recipes indexed: &a" + mod.sources.smeltCount() + "&7. Name an item id for its sources."); return; }
        String id = mod.index.canon(a[1].toUpperCase(Locale.ROOT));
        say(s, "&6" + id + "&7: grid recipes &a" + mod.index.producing(id).size() + "&7, used in &a" + mod.index.using(id).size());
        for (com.skyclone.api.item.Recipe r : mod.sources.others(id)) {
            say(s, " &e" + r.type + " &7" + (r.station == null ? "" : r.station + " ") + "&f" + r.inputs + (r.durationSeconds > 0 ? " &7" + r.durationSeconds + "s" : "")
                    + " &7unlock " + (r.unlock == null ? "&anone" : "&f" + r.unlock));
        }
        for (Sources.Smelt sm : mod.sources.smelting(id)) say(s, " &eFURNACE &f" + sm.inputs() + " &7-> " + sm.amount() + " in " + sm.cookTicks() + "t");
        for (Sources.Smelt sm : mod.sources.smeltsFrom(id)) say(s, " &7smelts into &f" + sm.result());
        for (String kind : new String[]{"slayer", "dungeon", "kuudra", "fishing"}) {      // wave 5: loot rolled in code
            for (LootTables.Line l : mod.sources.lootLines(kind, id)) say(s, " &e" + kind.toUpperCase(Locale.ROOT) + " " + l.text());
        }
    }

    private RecipeIndex.Entry entryFor(String key) {
        RecipeIndex.Entry e = mod.index.byId(key);
        if (e != null) return e;
        List<RecipeIndex.Entry> l = mod.index.producing(key.toUpperCase(Locale.ROOT));
        return l.isEmpty() ? null : l.get(0);
    }

    private void craft(CommandSender s, String[] a) {
        Player p = player(s, a, 1);
        if (p == null) return;
        if (a.length < 3) { say(s, "&cName an item or recipe id."); return; }
        RecipeIndex.Entry e = entryFor(a[2]);
        if (e == null) { say(s, "&cNo recipe for " + a[2]); return; }
        int times = a.length > 3 ? parseInt(a[3], 1) : 1;
        long before = mod.engine.data(p) == null ? 0 : mod.engine.data(p).crafted;
        int made = mod.engine.craftStock(p, e, times, true);
        long after = mod.engine.data(p) == null ? 0 : mod.engine.data(p).crafted;
        say(s, made > 0 ? "&aCrafted " + made + "x " + e.id() + " for " + p.getName() + " (+" + (after - before) + " items)"
                : "&cCould not craft " + e.id() + " for " + p.getName() + " (see their chat for why)");
    }

    private void ingredients(CommandSender s, String[] a) {
        Player p = player(s, a, 1);
        if (p == null) return;
        if (a.length < 3) { say(s, "&cName an item or recipe id."); return; }
        RecipeIndex.Entry e = entryFor(a[2]);
        if (e == null) { say(s, "&cNo recipe for " + a[2]); return; }
        int times = Math.max(1, Math.min(64, a.length > 3 ? parseInt(a[3], 1) : 1));
        for (Map.Entry<Pattern.Ing, Integer> en : e.pattern.totals().entrySet()) {
            String id = en.getKey().display();
            if (!Sky.items().exists(id)) { say(s, "&cSkipped unknown " + id); continue; }
            int total = en.getValue() * times;
            int max = Math.max(1, Sky.items().def(id).effectiveMaxStack());
            while (total > 0) {
                int n = Math.min(max, total);
                Sky.loot().give(p, Sky.items().create(id, n), LootSource.ADMIN);
                total -= n;
            }
        }
        say(s, "&aGave " + p.getName() + " the ingredients for " + times + "x " + e.id());
    }

    private void trade(CommandSender s, String[] a) {
        Player p = player(s, a, 1);
        if (p == null) return;
        if (a.length < 3) {
            StringBuilder sb = new StringBuilder("&7Trades:");
            for (Trades.Trade t : mod.trades.trades) sb.append(" ").append(t.id);
            say(s, sb.toString());
            return;
        }
        Trades.Trade t = mod.trades.byId(a[2]);
        if (t == null) { say(s, "&cNo trade " + a[2]); return; }
        int n = mod.trades.trade(p, t, a.length > 3 ? parseInt(a[3], 1) : 1);
        say(s, n > 0 ? "&aMade " + n + " trade(s) of " + t.id + " for " + p.getName() : "&cTrade failed (see their chat)");
    }

    private void tree(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&cName an item id."); return; }
        String id = mod.index.canon(a[1].toUpperCase(Locale.ROOT));
        long qty = a.length > 2 ? parseInt(a[2], 1) : 1;
        Map<String, Long> raw = CraftMath.raw(id, qty, x -> mod.book.treeRecipe(x, id), Math.max(1, mod.dials.treeDepth.get()));
        say(s, "&6Raw materials for " + qty + "x " + id + ":");
        raw.forEach((k, v) -> say(s, " &7- &f" + Text.commas(v) + "x " + k));
    }

    private void furniture(CommandSender s, String[] a) {
        if (a.length < 2 || a[1].equalsIgnoreCase("list")) {
            for (Furniture.Def d : mod.furniture.defs.values()) {
                say(s, "&7L" + d.level + " &f" + d.id + " &7" + d.name + " &8(" + d.offsets.size() + " blocks, " + d.seats.size() + " seats, "
                        + d.function + ")");
            }
            return;
        }
        if (a[1].equalsIgnoreCase("storage")) {
            // Wave 5: storage / showcase furniture keeps its items in crafting_furniture_store, one row per placed piece.
            say(s, "&7Storage table: " + (mod.furniture.store.dbOk ? "&aok" : "&cMISSING") + "&7, pieces loaded: &a"
                    + mod.furniture.store.loadedCount() + "&7, showcases displayed: &a" + mod.furniture.showCount());
            return;
        }
        if (a[1].equalsIgnoreCase("orphans")) {
            Player p = player(s, a, 2);
            if (p == null) return;
            mod.furniture.store.claimOrphans(p);
            say(s, "&aReturning any orphaned furniture items to " + p.getName() + " (they are told in chat).");
            return;
        }
        if (a[1].equalsIgnoreCase("give")) {
            Player p = player(s, a, 2);
            if (p == null) return;
            if (a.length < 4 || !mod.furniture.defs.containsKey(a[3].toUpperCase(Locale.ROOT))) { say(s, "&cName a furniture id (/sb crafting furniture list)."); return; }
            String id = a[3].toUpperCase(Locale.ROOT);
            if (!Sky.items().exists(id)) { say(s, "&cItem " + id + " is not registered."); return; }
            int n = Math.max(1, Math.min(16, a.length > 4 ? parseInt(a[4], 1) : 1));
            Sky.loot().give(p, Sky.items().create(id, n), LootSource.ADMIN);
            say(s, "&aGave " + n + "x " + id + " to " + p.getName());
            return;
        }
        if (a[1].equalsIgnoreCase("place")) {
            // Places on the block the player is looking at (within 6 blocks), facing the way they face, without
            // taking an item: the same checks as a player placing it (room, build rights).
            Player p = player(s, a, 2);
            if (p == null) return;
            Furniture.Def d = a.length > 3 ? mod.furniture.defs.get(a[3].toUpperCase(Locale.ROOT)) : null;
            if (d == null) { say(s, "&cName a furniture id (/sb crafting furniture list)."); return; }
            org.bukkit.block.Block target = p.getTargetBlockExact(6);
            if (target == null) { say(s, "&c" + p.getName() + " is not looking at a block."); return; }
            String why = mod.furniture.place(p, d, target.getRelative(org.bukkit.block.BlockFace.UP), p.getFacing(), false);
            say(s, why == null ? "&aPlaced " + d.id + " on top of " + target.getType() + " for " + p.getName() : "&c" + why);
            return;
        }
        say(s, "&c/sb crafting furniture list | give <player> <id> [n] | place <player> <id> | storage | orphans <player>");
    }

    private void check(CommandSender s) {
        String r1 = CraftSelfTests.unknownIngredients(mod, 12);
        say(s, r1 == null ? "&aEvery recipe ingredient resolves to an item." : "&c" + r1);
        String r2 = CraftSelfTests.coinLoops(mod, 12, 1.0);
        say(s, r2 == null ? "&aNo always-unlocked recipe sells to NPCs for more than its ingredients." : "&6" + r2);
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
