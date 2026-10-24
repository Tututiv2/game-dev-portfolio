package com.skyclone.mod.materials;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /sb materials ...} — everything prints synchronously so it shows over RCON.
 * <pre>
 *   /sb materials report                      what registered, what other modules own, skipped recipes
 *   /sb materials info &lt;id&gt;                   one item: owner, price, family, recipes, chain
 *   /sb materials chain &lt;id&gt;                  the chain with raw cost and value in/out per step
 *   /sb materials compress &lt;player&gt; &lt;id&gt; [n] run the compressor for a player (tests the real path)
 *   /sb materials open &lt;player&gt; [family|guide|quick|&lt;id&gt;]   open a catalogue menu for a player
 *   /sb materials unlocks on|off              toggle the recipe-unlock dial
 *   /sb materials skipped [n]                 list skipped recipes with the reason
 *   /sb materials printers                    compression recipes that sell for more than their inputs (live)
 *   /sb materials sellcap                     re-run the live sell cap and list what it lowered (wave 5)
 *   /sb materials consumables &lt;player&gt; [pepper &lt;n&gt;|filet &lt;minutes&gt;|reset]   peppers eaten, filet time (wave 5)
 * </pre>
 */
final class MaterialsAdmin {

    private MaterialsAdmin() {}

    static void register(MaterialsModule mod) {
        Sky.commands().admin("materials", "<report|info|chain|compress|open|unlocks|skipped|printers|sellcap|consumables> ...",
                "Materials catalogue: report, inspect chains, test compression",
                (s, a) -> run(mod, s, a),
                (s, a) -> complete(mod, a));
    }

    private static List<String> complete(MaterialsModule mod, String[] a) {
        if (a.length <= 1) return List.of("report", "info", "chain", "compress", "open", "unlocks", "skipped", "printers",
                "sellcap", "consumables");
        String sub = a[0].toLowerCase(Locale.ROOT);
        String last = a[a.length - 1].toUpperCase(Locale.ROOT);
        if ((sub.equals("info") || sub.equals("chain")) && a.length == 2) return ids(mod, last);
        if (sub.equals("compress") && a.length == 3) return ids(mod, last);
        if (sub.equals("consumables") && a.length == 3) return List.of("pepper", "filet", "reset");
        if ((sub.equals("compress") || sub.equals("open") || sub.equals("consumables")) && a.length == 2) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        if (sub.equals("open") && a.length == 3) {
            List<String> l = new ArrayList<>(MaterialsCatalogue.FAMILIES);
            l.add("guide"); l.add("quick");
            return l;
        }
        if (sub.equals("unlocks")) return List.of("on", "off");
        return List.of();
    }

    private static List<String> ids(MaterialsModule mod, String prefix) {
        List<String> out = new ArrayList<>();
        for (String id : mod.catalogue().defs.keySet()) {
            if (id.startsWith(prefix)) out.add(id);
            if (out.size() >= 40) break;
        }
        return out;
    }

    private static void say(CommandSender s, String legacy) { s.sendMessage(Text.c(legacy)); }

    private static void run(MaterialsModule mod, CommandSender s, String[] a) {
        String sub = a.length == 0 ? "report" : a[0].toLowerCase(Locale.ROOT);
        MaterialsCatalogue cat = mod.catalogue();
        switch (sub) {
            case "report" -> {
                say(s, "&6Materials catalogue");
                say(s, " &7Data: &a" + cat.defs.size() + " &7items, &a" + cat.recipes.size() + " &7recipes, &a"
                        + cat.chains.size() + " &7chains");
                say(s, " &7Registered by materials: &a" + cat.registered.size() + " &7(upgraded vanilla defs: &a"
                        + cat.replacedVanilla.size() + "&7)");
                say(s, " &7Owned by other modules: &e" + cat.ownedElsewhere.size());
                say(s, " &7Recipes registered: &a" + cat.registeredRecipes.size() + " &7skipped: &e" + cat.skippedRecipes.size());
                for (String fam : MaterialsCatalogue.FAMILIES) {
                    int n = 0, mine = 0;
                    for (String id : cat.family(fam)) { n++; if (cat.registered.contains(id)) mine++; }
                    say(s, "  &7" + fam + ": &a" + n + " &7(" + mine + " ours)");
                }
                say(s, " &7Dial recipe-unlocks: &a" + mod.dialRecipeUnlocks().get() + "&7, compress: &a" + mod.dialCompressEnabled().get());
                say(s, " &7Live sell cap lowered: &e" + cat.liveCapped.size() + " &7| Vanilla-book recipes: &a"
                        + cat.registeredRecipes.stream().filter(r -> r.id != null && r.id.startsWith("vanilla:")).count());
            }
            case "info" -> {
                if (a.length < 2) { say(s, "&cUsage: /sb materials info <id>"); return; }
                String id = a[1].toUpperCase(Locale.ROOT);
                ItemDef d = Sky.items().def(id);
                if (d == null) { say(s, "&cNo item " + id); return; }
                say(s, "&6" + id + " &7= " + MaterialsMenus.nameOf(id));
                String owner = cat.registered.contains(id) ? "materials" + (cat.replacedVanilla.contains(id) ? " (upgraded vanilla)" : "")
                        : cat.has(id) ? "another module (catalogue copy unused)" : "another module (not in catalogue)";
                say(s, " &7Owner: &a" + owner);
                say(s, " &7Material: &a" + d.material + " &7Rarity: " + d.rarity.code() + d.rarity.display()
                        + " &7Sell: &6" + Text.commas(d.npcSell) + " &7Stack: &a" + d.effectiveMaxStack());
                String fam = cat.familyOf(id);
                if (fam != null) say(s, " &7Family: &a" + fam);
                for (Recipe r : Compressor.recipesFor(id)) say(s, " &7Recipe &f" + r.id + "&7: " + describe(r));
                List<Recipe> using = Sky.recipes().using(id);
                say(s, " &7Used in &a" + using.size() + " &7recipe(s)");
                if (cat.previous(id) != null || !cat.next(id).isEmpty()) {
                    say(s, " &7Chain: &f" + String.join(" &8> &f", mod.menus().chain(cat.root(id))));
                }
            }
            case "chain" -> {
                if (a.length < 2) { say(s, "&cUsage: /sb materials chain <id>"); return; }
                String id = a[1].toUpperCase(Locale.ROOT);
                if (!cat.has(id)) { say(s, "&cNot a catalogue item: " + id); return; }
                String root = cat.root(id);
                List<String> chain = mod.menus().chain(root);
                say(s, "&6Chain from " + root + " &7(" + chain.size() + " steps)");
                for (String step : chain) {
                    double raw = step.equals(root) ? 1 : mod.menus().rawOf(step, root);
                    double val = MaterialsMenus.priceOf(step);
                    double rawVal = raw * MaterialsMenus.priceOf(root);
                    say(s, " &f" + step + " &7= &a" + Text.commas(raw) + "x " + root + " &7| sells &6" + Text.commas(val)
                            + " &7vs raw &6" + Text.commas(rawVal) + (val > rawVal + 1e-6 ? " &c(PRINTER)" : ""));
                }
            }
            case "compress" -> {
                if (a.length < 3) { say(s, "&cUsage: /sb materials compress <player> <id> [times]"); return; }
                Player p = Bukkit.getPlayerExact(a[1]);
                if (p == null) { say(s, "&cPlayer not online: " + a[1]); return; }
                String id = a[2].toUpperCase(Locale.ROOT);
                int times = 1;
                if (a.length > 3) {
                    try { times = Math.max(1, Integer.parseInt(a[3])); } catch (NumberFormatException e) { say(s, "&cBad number"); return; }
                }
                List<Recipe> rs = Compressor.recipesFor(id);
                if (rs.isEmpty()) { say(s, "&cNo crafting recipe makes " + id); return; }
                Recipe best = rs.get(0);
                for (Recipe r : rs) if (mod.compressor().craftable(p, r, 1) > 0) { best = r; break; }
                Compressor.Result res = mod.compressor().compress(p, best, times);
                say(s, (res.ok() ? "&aOK " : "&cFAILED ") + "&7" + Text.strip(res.message()) + " &8(" + res.crafts() + " crafts, "
                        + res.produced() + " made, recipe " + best.id + ")");
                mod.tell(p, res);
            }
            case "open" -> {
                if (a.length < 2) { say(s, "&cUsage: /sb materials open <player> [family|guide|quick|<id>]"); return; }
                Player p = Bukkit.getPlayerExact(a[1]);
                if (p == null) { say(s, "&cPlayer not online: " + a[1]); return; }
                String what = a.length > 2 ? a[2] : "";
                if (what.isEmpty()) mod.menus().openMain(p);
                else if (what.equalsIgnoreCase("guide")) mod.menus().openGuide(p, 0);
                else if (what.equalsIgnoreCase("quick")) mod.menus().openQuick(p, 0);
                else if (MaterialsCatalogue.FAMILIES.contains(what.toLowerCase(Locale.ROOT))) mod.menus().openFamily(p, what.toLowerCase(Locale.ROOT), 0);
                else if (Sky.items().exists(what.toUpperCase(Locale.ROOT))) mod.menus().openDetail(p, what.toUpperCase(Locale.ROOT), null);
                else { say(s, "&cUnknown menu or id: " + what); return; }
                say(s, "&aOpened for " + p.getName());
            }
            case "unlocks" -> {
                if (a.length < 2) { say(s, "&7recipe-unlocks = &a" + mod.dialRecipeUnlocks().get()); return; }
                boolean on = a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("true");
                mod.dialRecipeUnlocks().set(on);
                mod.applyUnlocks();
                say(s, "&aCatalogue recipes " + (on ? "need their collection tiers again." : "are all unlocked now."));
            }
            case "skipped" -> {
                int n = 20;
                if (a.length > 1) try { n = Integer.parseInt(a[1]); } catch (NumberFormatException ignored) {}
                say(s, "&6Skipped recipes: &e" + cat.skippedRecipes.size());
                int i = 0;
                for (Map.Entry<String, String> e : cat.skippedRecipes.entrySet()) {
                    if (i++ >= n) break;
                    say(s, " &f" + e.getKey() + " &7- " + e.getValue());
                }
            }
            case "printers" -> {
                int found = 0;
                for (Recipe r : cat.registeredRecipes) {
                    if (ChainMath.isCoinPrinter(r, Compressor::known, MaterialsMenus::priceOf)) {
                        found++;
                        say(s, " &c" + r.id + " &7in &6" + Text.commas(ChainMath.valueIn(r, Compressor::known, MaterialsMenus::priceOf))
                                + " &7out &6" + Text.commas(ChainMath.valueOut(r, MaterialsMenus::priceOf)));
                    }
                }
                say(s, found == 0 ? "&aNo compression sells for more than its inputs." : "&c" + found + " coin printer(s).");
            }
            case "sellcap" -> {
                mod.applyLiveSellCap();
                say(s, "&6Live sell cap: &e" + cat.liveCapped.size() + " &7catalogue price(s) lowered to their inputs' value"
                        + (mod.dialLiveSellCap().get() ? "" : " &8(dial materials.live-sell-cap is off; ran anyway)"));
                for (Map.Entry<String, Double> e : cat.liveCapped.entrySet()) {
                    say(s, " &f" + e.getKey() + " &7" + Text.commas(e.getValue()) + " &8-> &6" + Text.commas(MaterialsMenus.priceOf(e.getKey())));
                }
            }
            case "consumables" -> {
                if (a.length < 2) { say(s, "&cUsage: /sb materials consumables <player> [pepper <n>|filet <minutes>|reset]"); return; }
                Player p = Bukkit.getPlayerExact(a[1]);
                if (p == null) { say(s, "&cPlayer not online: " + a[1]); return; }
                if (a.length == 2) { say(s, "&6" + p.getName() + ": " + mod.consumables().describe(p)); return; }
                int n = 0;
                if (a.length > 3) {
                    try { n = Integer.parseInt(a[3]); } catch (NumberFormatException e) { say(s, "&cBad number"); return; }
                }
                say(s, mod.consumables().set(p, a[2].toLowerCase(Locale.ROOT), n));
            }
            default -> say(s, "&cUnknown sub. Try report, info, chain, compress, open, unlocks, skipped, printers, sellcap, consumables.");
        }
    }

    static String describe(Recipe r) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : ChainMath.ingredients(r, Compressor::known).entrySet()) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(e.getValue()).append("x ").append(e.getKey());
        }
        sb.append(" -> ").append(r.amount).append("x ").append(r.result);
        if (r.unlock != null) sb.append(" [").append(r.unlock).append("]");
        return sb.toString();
    }
}
