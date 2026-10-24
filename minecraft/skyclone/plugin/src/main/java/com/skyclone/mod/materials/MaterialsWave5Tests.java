package com.skyclone.mod.materials;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import org.bukkit.Material;
import org.bukkit.plugin.RegisteredListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Wave 5 (2026-10-06) live checks, run by {@code /sb selftest materials}: the live sell cap, the new integration
 * fallbacks, the fixed looks and names, the Vanilla recipe category, and the consumables. Null = pass.
 */
final class MaterialsWave5Tests {

    private MaterialsWave5Tests() {}

    /** Ingredients/trade goods materials now ships fallbacks for (gen_integration.py, wave 5). */
    static final List<String> WAVE5_FALLBACKS = List.of("GOLDEN_FRAGMENT", "WINTER_FRAGMENT", "VOLCANIC_ROCK",
            "SHIELD_DUST", "PLASMA_NUCLEUS", "OMEGA_EGG", "EXPORTABLE_CARROTS", "COLOSSAL_EXP_BOTTLE_UPGRADE");

    /** Legacy ids whose look was wrong (Player Head drawn as bamboo...) and the 1.8 names renamed for shops. */
    static final String[][] LOOKS = {
            {"SKULL_ITEM:1", "WITHER_SKELETON_SKULL", "Wither Skeleton Skull"},
            {"SKULL_ITEM:3", "PLAYER_HEAD", "Player Head"},
            {"SKULL_ITEM:5", "DRAGON_HEAD", "Dragon Head"},
            {"GOLDEN_APPLE:1", "ENCHANTED_GOLDEN_APPLE", "Enchanted Golden Apple"},
            {"SANDSTONE:2", "CUT_SANDSTONE", "Cut Sandstone"},
            {"RED_SANDSTONE:2", "CUT_RED_SANDSTONE", "Cut Red Sandstone"},
            {"STEP", "SMOOTH_STONE_SLAB", "Smooth Stone Slab"},
    };

    static void register(MaterialsModule mod) {
        MaterialsCatalogue cat = mod.catalogue();

        Sky.commands().selfTest("materials.live-sell-cap", () -> {
            String pure = pureCapRule();
            if (pure != null) return pure;
            if (!mod.dialLiveSellCap().get()) return null;
            List<String> bad = new ArrayList<>();
            for (Recipe r : cat.registeredRecipes) {
                if (!cat.registered.contains(r.result) || !mod.compressor().isCompression(r)) continue;
                double cap = ChainMath.cappedPrice(r, Compressor::known, MaterialsMenus::priceOf);
                double now = MaterialsMenus.priceOf(r.result);
                if (cap >= 0 && cap < now - 1e-9) bad.add(r.result + " sells " + now + " > " + cap);
            }
            for (String id : cat.liveCapped.keySet()) {
                ItemDef d = Sky.items().def(id);
                if (d == null || d.extra == null || !d.extra.has("referencePrice")) bad.add(id + " lost its referencePrice");
            }
            return bad.isEmpty() ? null : bad.size() + " still sell for more than their inputs: " + MaterialsSelfTests.head(bad);
        });

        Sky.commands().selfTest("materials.wave5-fallbacks", () -> {
            List<String> bad = new ArrayList<>();
            for (String id : WAVE5_FALLBACKS) {
                if (!cat.has(id)) { bad.add(id + " not in the catalogue data"); continue; }
                if (!Sky.items().exists(id)) bad.add(id + " not registered by anyone");
            }
            for (String id : List.of("PLASMA_NUCLEUS", "OMEGA_EGG", "COLOSSAL_EXP_BOTTLE_UPGRADE")) {
                ItemDef d = Sky.items().def(id);
                if (d != null && cat.registered.contains(id) && d.effectiveMaxStack() != 1) bad.add(id + " stacks (reference: one per slot)");
            }
            // the reason they exist: another module's recipe asks for them (only meaningful with crafting installed)
            if (Sky.recipes().all().size() > 1500) {
                for (String id : List.of("GOLDEN_FRAGMENT", "PLASMA_NUCLEUS", "OMEGA_EGG")) {
                    if (Sky.items().exists(id) && Sky.recipes().using(id).isEmpty()) bad.add("no live recipe uses " + id);
                }
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("materials.looks-and-names", () -> {
            List<String> bad = new ArrayList<>();
            for (String[] l : LOOKS) {
                ItemDef data = cat.dataDef(l[0]);
                if (data == null) { bad.add(l[0] + " missing from the data"); continue; }
                if (!l[1].equals(data.material)) bad.add(l[0] + " looks like " + data.material + " (want " + l[1] + ")");
                if (!l[2].equals(data.name)) bad.add(l[0] + " is named " + data.name + " (want " + l[2] + ")");
                ItemDef live = Sky.items().def(l[0]);
                if (live != null && cat.registered.contains(l[0]) && !l[1].equals(live.material)) {
                    bad.add(l[0] + " live material " + live.material);
                }
                if (!cat.registered.contains(l[0])) continue;
                Material m = Material.matchMaterial(l[1]);
                if (m == null) { bad.add(l[1] + " is not a material on this server"); continue; }
                // a vanilla pickup may become the catalogue id or stay vanilla, never another item with another look
                String got = Sky.items().vanillaId(m);
                ItemDef gd = got == null ? null : Sky.items().def(got);
                if (gd != null && !got.equals(l[0]) && !got.equals(l[1]) && !l[1].equals(gd.material)) {
                    bad.add(l[1] + " pickups become " + got);
                }
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("materials.vanilla-recipes", () -> {
            int van = 0;
            List<String> bad = new ArrayList<>();
            for (Recipe r : cat.recipes) {
                if (r.id == null || !r.id.startsWith("vanilla:")) continue;
                van++;
                if (r.result.startsWith("ENCHANTED_")) bad.add(r.id + " is a compression");
                for (String id : ChainMath.ingredients(r, Compressor::known).keySet()) {
                    if (id.startsWith("ENCHANTED_")) bad.add(r.id + " uses " + id);
                }
            }
            if (van < 100) bad.add("only " + van + " Vanilla-book recipes (expected ~280: planks, blocks, sticks...)");
            for (String id : List.of("ENCHANTED_DIAMOND", "ENCHANTED_COBBLESTONE", "ENCHANTED_DIAMOND_BLOCK")) {
                for (Recipe r : cat.dataRecipesFor(id)) if (r.id.startsWith("vanilla:")) bad.add(r.id + " filed under Vanilla");
            }
            return bad.isEmpty() ? null : String.join("; ", bad.subList(0, Math.min(6, bad.size())));
        });

        Sky.commands().selfTest("materials.consumables-wired", () -> {
            List<String> bad = new ArrayList<>();
            for (String[] w : Consumables.WIRED) {
                ItemDef data = cat.dataDef(w[0]);
                if (data == null || !hasAbility(data, w[1])) { bad.add(w[0] + " data has no ability " + w[1]); continue; }
                ItemDef live = Sky.items().def(w[0]);
                if (live != null && cat.registered.contains(w[0]) && !hasAbility(live, w[1])) bad.add(w[0] + " is ours but lost its ability");
            }
            ItemDef filet = cat.dataDef(Consumables.FILET);
            if (filet != null && (filet.requirements == null || !filet.requirements.contains("hotm:4"))) bad.add("Filet O' Fortune must need hotm:4");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("materials.consumables-stats", MaterialsWave5Tests::pureConsumables);

        Sky.commands().selfTest("materials.saving-grace", () -> {
            boolean listening = false;
            for (RegisteredListener rl : SkyEvents.SkyDeath.getHandlerList().getRegisteredListeners()) {
                if (rl.getListener() instanceof Consumables) { listening = true; break; }
            }
            if (!listening) return "nothing listens for SkyDeath on behalf of the Saving Grace";
            ItemDef data = cat.dataDef(Consumables.GRACE);
            if (data == null) return "SAVING_GRACE is not in the catalogue";
            boolean passive = data.abilities != null && data.abilities.stream()
                    .anyMatch(a -> Consumables.A_GRACE.equals(a.id) && a.trigger == AbilityDef.Trigger.PASSIVE);
            return passive ? null : "SAVING_GRACE must carry the passive " + Consumables.A_GRACE;
        });
    }

    static boolean hasAbility(ItemDef d, String id) {
        return d.abilities != null && d.abilities.stream().anyMatch(a -> id.equals(a.id));
    }

    /** The cap rule on recipes made up here (no server state). */
    static String pureCapRule() {
        Map<String, Double> px = Map.of("SOULFLOW", 1.0, "RAW_SOULFLOW", 0.0, "A", 2.0, "B", 100.0, "C", 50.0);
        Function<String, Double> price = id -> px.getOrDefault(id, 0.0);
        Predicate<String> known = px::containsKey;
        if (ChainMath.cappedPrice(synthetic("SOULFLOW", 1, "RAW_SOULFLOW:32", "RAW_SOULFLOW:32"), known, price) != 0) {
            return "a compression of unsellable inputs must cap its result at 0";
        }
        if (Math.abs(ChainMath.cappedPrice(synthetic("B", 1, "A:32", "A:8"), known, price) - 80) > 1e-9) {
            return "a 100-coin result of 80 coins of input must cap at 80";
        }
        if (ChainMath.cappedPrice(synthetic("C", 1, "A:32"), known, price) != -1) return "a fair recipe must not be capped";
        if (ChainMath.cappedPrice(synthetic("C", 1, "A:1", "RAW_SOULFLOW:1"), known, price) != -1) return "a half-priced recipe has no cap";
        if (Math.abs(ChainMath.cappedPrice(synthetic("B", 3, "A:32"), known, price) - 21.3) > 1e-9) return "the cap must round DOWN to a tenth per item";
        return null;
    }

    /** The consumables' stat and duration rules. */
    static String pureConsumables() {
        Consumables.Data d = new Consumables.Data();
        long now = 1_000_000L;
        d.reaperPeppers = 3;
        StatMap m = new StatMap();
        Consumables.contribute(d, now, 20, 5, m);
        if (m.flat(Stat.HEALTH) != 3 || m.flat(Stat.COLD_RESISTANCE) != 3) return "3 peppers must be +3 Health and +3 Cold Resistance";
        if (m.flat(Stat.MINING_FORTUNE) != 0) return "no filet eaten, but Mining Fortune moved";
        d.reaperPeppers = 9;
        d.filetUntil = now + 1;
        m = new StatMap();
        Consumables.contribute(d, now, 20, 5, m);
        if (m.flat(Stat.HEALTH) != 5) return "the pepper cap must hold (9 eaten, cap 5)";
        if (m.flat(Stat.MINING_FORTUNE) != 20) return "an active filet must give its Mining Fortune";
        d.filetUntil = now;
        m = new StatMap();
        Consumables.contribute(d, now, 20, 5, m);
        if (m.flat(Stat.MINING_FORTUNE) != 0) return "an expired filet must give nothing";
        if (Consumables.filetExpiry(now + 9_000_000, now, 3_600_000) != now + 9_000_000) return "eating a filet shortened a longer one";
        if (Consumables.filetExpiry(0, now, 3_600_000) != now + 3_600_000) return "a filet must last its duration";
        // Delver's O's cereals: +2 per cereal, first 5 of EACH kind (audit 2026-10-06 mining follow-up)
        d.cereals.put("DWARVEN_OS_ORE_OATS", 3);
        d.cereals.put("DWARVEN_OS_METALLIC_MINIS", 9);
        m = new StatMap();
        Consumables.contributeCereals(d, 5, 2, m);
        if (m.flat(Stat.ORE_FORTUNE) != 6) return "3 Ore Oats must be +6 Ore Fortune";
        if (m.flat(Stat.DWARVEN_METAL_FORTUNE) != 10) return "the cereal cap must hold (9 Minis eaten, cap 5 -> +10)";
        if (m.flat(Stat.BLOCK_FORTUNE) != 0 || m.flat(Stat.GEMSTONE_FORTUNE) != 0) return "an uneaten cereal moved a stat";
        return null;
    }

    /** A made-up crafting recipe for the pure checks. */
    static Recipe synthetic(String result, int amount, String... cells) {
        Recipe r = new Recipe();
        r.id = "materials:selftest";
        r.type = Recipe.Type.CRAFTING;
        r.result = result;
        r.amount = amount;
        r.grid = new String[9];
        for (int i = 0; i < cells.length && i < 9; i++) r.grid[i] = cells[i];
        return r;
    }
}
