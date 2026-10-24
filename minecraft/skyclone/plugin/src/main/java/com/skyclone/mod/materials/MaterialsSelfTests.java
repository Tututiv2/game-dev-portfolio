package com.skyclone.mod.materials;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.ui.Menu;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live checks run by {@code /sb selftest materials}. Each returns null on success or a short reason.
 * They check that the catalogue's DATA hangs together in the running game, not just in the JSON.
 */
final class MaterialsSelfTests {

    private MaterialsSelfTests() {}

    /** Research 02 §5.2/§5.3: raw inputs per compression, and one NPC price. */
    static final Object[][] RESEARCH = {
            // result, base, raw base per result
            {"ENCHANTED_DIAMOND", "DIAMOND", 160.0},
            {"ENCHANTED_DIAMOND_BLOCK", "DIAMOND", 25_600.0},
            {"ENCHANTED_ENDER_PEARL", "ENDER_PEARL", 20.0},
            {"ABSOLUTE_ENDER_PEARL", "ENCHANTED_ENDER_PEARL", 80.0},
            {"ENCHANTED_HARD_STONE", "HARD_STONE", 576.0},
            {"CONCENTRATED_STONE", "ENCHANTED_HARD_STONE", 576.0},
            {"ENCHANTED_BREAD", "WHEAT", 60.0},
            {"ENCHANTED_EGG", "EGG", 144.0},
            {"SUPER_EGG", "ENCHANTED_EGG", 144.0},
            {"SILVER_FANG", "ENCHANTED_GHAST_TEAR", 25.0},
            // research 02 contradicts itself here (§5.2 says 192, §4.5 says 160); the NEU recipe data says 160
            {"ENCHANTED_GLOWSTONE", "ENCHANTED_GLOWSTONE_DUST", 160.0},
            {"ENCHANTED_SUGAR_CANE", "SUGAR_CANE", 25_600.0},
            {"ENCHANTED_COBBLESTONE", "COBBLESTONE", 160.0},
            {"ENCHANTED_SPONGE", "SPONGE", 40.0},
    };

    static void register(MaterialsModule mod) {
        MaterialsCatalogue cat = mod.catalogue();
        MaterialsWave5Tests.register(mod);

        Sky.commands().selfTest("materials.catalogue-loaded", () -> {
            if (!cat.loaded) return "catalogue data never loaded";
            if (!cat.missingFiles.isEmpty()) return "data files missing from the jar: " + cat.missingFiles
                    + " (rerun py -3 tools/gen/materials/gen_materials.py)";
            if (cat.defs.size() < 1000) return "only " + cat.defs.size() + " catalogue items loaded (expected 1,300+)";
            if (cat.recipes.size() < 400) return "only " + cat.recipes.size() + " recipes loaded";
            for (String fam : MaterialsCatalogue.FAMILIES) if (cat.family(fam).isEmpty()) return "family " + fam + " is empty";
            return null;
        });

        Sky.commands().selfTest("materials.items-exist", () -> {
            List<String> missing = new ArrayList<>();
            for (String id : cat.defs.keySet()) if (!Sky.items().exists(id)) missing.add(id);
            for (List<String> l : cat.chains.values()) for (String id : l) if (!Sky.items().exists(id)) missing.add(id);
            return missing.isEmpty() ? null : missing.size() + " catalogue ids not registered: " + head(missing);
        });

        Sky.commands().selfTest("materials.recipes-ingredients-exist", () -> {
            List<String> bad = new ArrayList<>();
            for (Recipe r : cat.registeredRecipes) {
                if (!Sky.items().exists(r.result)) bad.add(r.id + " result " + r.result);
                for (String id : ChainMath.ingredients(r, Compressor::known).keySet()) {
                    if (!Sky.items().exists(id)) bad.add(r.id + " needs " + id);
                }
            }
            return bad.isEmpty() ? null : bad.size() + " bad recipe cells: " + head(bad);
        });

        Sky.commands().selfTest("materials.enchanted-have-recipes", () -> {
            List<String> none = new ArrayList<>();
            for (String id : cat.defs.keySet()) {
                var d = cat.defs.get(id);
                boolean bought = d != null && d.tags != null && d.tags.contains("no_recipe");   // shop/trade-only by design
                if (id.startsWith("ENCHANTED_") && !bought && Sky.recipes().producing(id).isEmpty()) none.add(id);
            }
            return none.isEmpty() ? null : none.size() + " ENCHANTED_ items nobody can craft: " + head(none);
        });

        Sky.commands().selfTest("materials.research-numbers", () -> {
            List<String> bad = new ArrayList<>();
            for (Object[] row : RESEARCH) {
                String result = (String) row[0], base = (String) row[1];
                double want = (Double) row[2];
                if (!Sky.items().exists(result)) { bad.add(result + " missing"); continue; }
                Map<String, Double> raw = ChainMath.rawCost(result, mod.menus().chainRecipe(), Compressor::known, Set.of(base));
                Double got = raw.get(base);
                if (got == null || Math.abs(got - want) > 1e-6) bad.add(result + " = " + got + "x " + base + " (research " + want + ")");
            }
            ItemDef ed = Sky.items().def("ENCHANTED_DIAMOND");
            if (ed == null || Math.abs(ed.npcSell - 1280) > 1e-6) bad.add("ENCHANTED_DIAMOND sells " + (ed == null ? "?" : ed.npcSell) + " (research 1,280)");
            ItemDef dia = Sky.items().def("DIAMOND");
            if (dia == null || Math.abs(dia.npcSell - 8) > 1e-6) bad.add("DIAMOND sells " + (dia == null ? "?" : dia.npcSell) + " (research 8)");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("materials.no-coin-printer", () -> {
            List<String> bad = new ArrayList<>();
            for (Recipe r : cat.registeredRecipes) {
                if (!mod.compressor().isCompression(r)) continue;
                if (ChainMath.isCoinPrinter(r, Compressor::known, MaterialsMenus::priceOf)) {
                    bad.add(r.id + " (in " + ChainMath.valueIn(r, Compressor::known, MaterialsMenus::priceOf)
                            + ", out " + ChainMath.valueOut(r, MaterialsMenus::priceOf) + ")");
                }
            }
            return bad.isEmpty() ? null : bad.size() + " compressions sell for more than their inputs: " + head(bad);
        });

        Sky.commands().selfTest("materials.unlock-keys", () -> {
            List<String> bad = new ArrayList<>();
            boolean coll = Sky.has(Features.Collections.class);
            for (Map.Entry<Recipe, String> e : cat.originalUnlock.entrySet()) {
                String key = e.getValue();
                if (key == null) continue;
                String[] p = ChainMath.splitUnlock(key);
                if (p == null) { bad.add(e.getKey().id + " malformed " + key); continue; }
                switch (p[0]) {
                    case "collection" -> {
                        if (!Sky.items().exists(p[1])) bad.add(key + " (no item " + p[1] + ")");
                        else if (coll && !Sky.service(Features.Collections.class).isCollectionItem(p[1])) bad.add(key + " (not a collection)");
                        int[] tiers = cat.collectionTiers.get(p[1]);
                        int t;
                        try { t = Integer.parseInt(p[2]); } catch (NumberFormatException ex) { t = -1; }
                        if (t < 1 || (tiers != null && t > tiers.length)) bad.add(key + " (tier out of range)");
                    }
                    case "slayer", "skill" -> {
                        try { if (Integer.parseInt(p[2]) < 1) bad.add(key); } catch (NumberFormatException ex) { bad.add(key); }
                    }
                    default -> bad.add(key + " (unknown prefix)");
                }
            }
            return bad.isEmpty() ? null : bad.size() + " bad unlock keys: " + head(bad);
        });

        Sky.commands().selfTest("materials.vanilla-pickups", () -> {
            // a vanilla pickup of these materials must become the catalogue's reference id
            Object[][] want = {
                    {Material.OAK_LOG, "LOG"}, {Material.COCOA_BEANS, "INK_SACK:3"}, {Material.GUNPOWDER, "SULPHUR"},
                    {Material.MELON_SLICE, "MELON"}, {Material.MELON, "MELON_BLOCK"}, {Material.WHEAT_SEEDS, "SEEDS"},
                    {Material.CARROT, "CARROT_ITEM"}, {Material.DIAMOND, "DIAMOND"}, {Material.LAPIS_LAZULI, "INK_SACK:4"},
            };
            List<String> bad = new ArrayList<>();
            for (Object[] w : want) {
                String id = (String) w[1];
                if (!Sky.items().exists(id)) continue; // not ours to check if nobody registered it
                String got = Sky.items().vanillaId((Material) w[0]);
                if (!id.equals(got)) bad.add(w[0] + " -> " + got + " (want " + id + ")");
            }
            ItemDef pearl = Sky.items().def("ENDER_PEARL");
            if (pearl != null && cat.registered.contains("ENDER_PEARL") && pearl.effectiveMaxStack() != 16) {
                bad.add("ENDER_PEARL stacks to " + pearl.effectiveMaxStack() + " (vanilla 16)");
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("materials.menus-build", () -> {
            Menu main = mod.menus().buildMain(null);
            if (main.get(19) == null) return "main menu has no family icons";
            for (String id : List.of("ENCHANTED_DIAMOND", "ENCHANTED_DIAMOND_BLOCK", "LOG", "INK_SACK:3")) {
                if (!Sky.items().exists(id)) continue;
                Menu d = mod.menus().buildDetail(null, id, null, 0);
                if (d.get(4) == null) return "detail menu for " + id + " has no header";
                if (!Compressor.recipesFor(id).isEmpty() && d.get(MaterialsMenus.GRID[4]) == null) return "detail menu for " + id + " drew no recipe";
            }
            for (String fam : MaterialsCatalogue.FAMILIES) {
                List<String> ids = cat.family(fam);
                Menu page = Sky.menus().paged(fam, ids.subList(0, Math.min(28, ids.size())), MaterialsMenus::icon1, null, null, 0);
                if (!ids.isEmpty() && page.get(10) == null) return "family page " + fam + " drew nothing";
            }
            return null;
        });
    }

    static String head(List<String> l) {
        return String.join(", ", l.subList(0, Math.min(8, l.size()))) + (l.size() > 8 ? ", ..." : "");
    }
}
