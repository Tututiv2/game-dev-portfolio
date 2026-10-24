package com.skyclone.mod.materials;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.config.Dials;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.ui.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fallback catalogue: every generic material, vanilla-derived resource and ENCHANTED_* /
 * compacted chain of the genre, with its compression recipes.
 *
 * <p>It registers LAST and only what is absent (postEnable), so any specialist module's definition
 * of the same id wins. Plain vanilla defs that core made automatically (id = Material name, no
 * price, no lore) are upgraded with the catalogue's version, which is what gives Diamond its NPC
 * price and "Collection Item" line.
 *
 * <p>Player side: {@code /materials} (alias {@code /mats}) opens the Materials Catalogue — browse by
 * family, search, see each compression chain with its raw cost and value, and compress straight
 * from the inventory ({@code /compress}).
 */
public final class MaterialsModule implements GameModule {

    private final MaterialsCatalogue catalogue = new MaterialsCatalogue();
    private final Compressor compressor = new Compressor(this);
    private final Consumables consumables = new Consumables(this);
    private MaterialsMenus menus;

    private Dials.Dial<Boolean> registerItems;
    private Dials.Dial<Boolean> replaceVanilla;
    private Dials.Dial<Boolean> registerRecipes;
    private Dials.Dial<Boolean> recipeUnlocks;
    private Dials.Dial<Boolean> compressEnabled;
    private Dials.Dial<Integer> maxPerClick;
    private Dials.Dial<Integer> cooldownMs;
    private Dials.Dial<Boolean> fromSacks;
    private Dials.Dial<Boolean> vanillaStacks;
    private Dials.Dial<Boolean> liveSellCap;

    @Override public String id() { return "materials"; }

    @Override
    public void enable() {
        registerItems = Sky.dials().flag("materials.register-items", true,
                "Register the fallback material catalogue at boot (only ids no other module registered). Restart to apply.");
        replaceVanilla = Sky.dials().flag("materials.replace-vanilla", true,
                "Upgrade core's plain vanilla item defs (no price/lore) with the catalogue's version. Restart to apply.");
        registerRecipes = Sky.dials().flag("materials.register-recipes", true,
                "Register compression recipes for catalogue items no other module already makes. Restart to apply.");
        recipeUnlocks = Sky.dials().flag("materials.recipe-unlocks", true,
                "Compression recipes need their collection tier (false = every catalogue recipe is unlocked). Live.");
        compressEnabled = Sky.dials().flag("materials.compress-enabled", true,
                "Allow compressing straight from the inventory via /compress and the catalogue. Live.");
        maxPerClick = Sky.dials().integer("materials.compress-max-per-click", 64,
                "Most crafts one click of Compress All may perform (keeps a huge inventory from lagging a tick). Live.");
        cooldownMs = Sky.dials().integer("materials.compress-cooldown-ms", 250,
                "Minimum time between two compress actions of one player, in milliseconds. Live.");
        fromSacks = Sky.dials().flag("materials.compress-from-sacks", false,
                "Compressing may also take inputs from the player's sacks (when a sacks module is installed). Live.");
        vanillaStacks = Sky.dials().flag("materials.vanilla-stack-sizes", true,
                "Vanilla-equivalent catalogue items keep their vanilla stack size (16 ender pearls). Restart to apply.");
        liveSellCap = Sky.dials().flag("materials.live-sell-cap", true,
                "After every module registered, lower the NPC price of any catalogue item a compression makes so it never "
                + "sells for more than its inputs at their LIVE prices (other modules may price inputs lower). Restart to apply.");
        recipeUnlocks.onChange(v -> applyUnlocks());

        catalogue.load();
        menus = new MaterialsMenus(this);

        Sky.commands().register(SkyCommand.of("materials", "Open the Materials Catalogue (every material and compression chain)")
                .alias("mats", "catalogue").category("Items").playerOnly().usage("[search]")
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (a.length > 0) menus.openSearch(p, String.join(" ", a), 0);
                    else menus.openMain(p);
                })
                .tab((s, a) -> List.of()));
        Sky.commands().register(SkyCommand.of("compress", "Compress raw materials in your inventory into enchanted forms")
                .category("Items").playerOnly()
                .run((s, a) -> menus.openQuick((Player) s, 0)));

        consumables.enable();
        MaterialsAdmin.register(this);
        MaterialsSelfTests.register(this);
    }

    @Override
    public void postEnable() {
        registerAll();
    }

    // =========================================================================================
    // registration (postEnable: every other module has registered its items and recipes by now)
    // =========================================================================================

    void registerAll() {
        if (registerItems.get()) {
            for (ItemDef d : catalogue.defs.values()) {
                boolean exists = Sky.items().exists(d.id);
                boolean auto = exists && isAutoVanilla(d.id);
                if (exists && !(auto && replaceVanilla.get())) {
                    catalogue.ownedElsewhere.add(d.id);
                    continue;
                }
                applyVanillaStack(d);
                Sky.items().register(d);
                if (Sky.items().def(d.id) == d) {
                    catalogue.registered.add(d.id);
                    if (auto) catalogue.replacedVanilla.add(d.id);
                }
            }
        }
        if (registerRecipes.get()) {
            Set<String> producedBefore = new HashSet<>();
            for (Recipe r : Sky.recipes().all()) if (r.result != null) producedBefore.add(r.result);
            for (Recipe r : catalogue.recipes) {
                if (producedBefore.contains(r.result)) {
                    catalogue.skippedRecipes.put(r.id, "another module already makes " + r.result);
                    continue;
                }
                String missing = firstMissing(r);
                if (missing != null) {
                    catalogue.skippedRecipes.put(r.id, "ingredient " + missing + " is not registered");
                    continue;
                }
                catalogue.originalUnlock.put(r, r.unlock);
                int before = Sky.recipes().all().size();
                Sky.recipes().register(r);
                if (Sky.recipes().all().size() > before) catalogue.registeredRecipes.add(r);
            }
            applyUnlocks();
        }
        if (liveSellCap.get()) applyLiveSellCap();
        Sky.log().info("[materials] registered " + catalogue.registered.size() + " items ("
                + catalogue.replacedVanilla.size() + " upgraded vanilla defs, " + catalogue.ownedElsewhere.size()
                + " owned by other modules), " + catalogue.registeredRecipes.size() + " recipes ("
                + catalogue.skippedRecipes.size() + " skipped)");
    }

    private String firstMissing(Recipe r) {
        if (!Sky.items().exists(r.result)) return r.result;
        for (String id : ChainMath.ingredients(r, Compressor::known).keySet()) if (!Sky.items().exists(id)) return id;
        return null;
    }

    /** Vanilla-equivalent items keep vanilla's stack size (ender pearls 16, buckets 1). */
    private void applyVanillaStack(ItemDef d) {
        if (!vanillaStacks.get() || d.maxStack > 0) return;
        boolean vanillaLike = d.id.equals(d.material) || (d.extra != null && d.extra.has("vanilla"));
        if (!vanillaLike) return;
        Material m = Material.matchMaterial(d.material);
        if (m != null && !m.isLegacy() && m.isItem()) {
            int ms = m.getMaxStackSize();
            if (ms > 0 && ms < 64) d.maxStack = ms;
        }
    }

    void applyUnlocks() {
        boolean on = recipeUnlocks.get();
        for (Map.Entry<Recipe, String> e : catalogue.originalUnlock.entrySet()) e.getKey().unlock = on ? e.getValue() : null;
    }

    /**
     * Core marks its automatic vanilla defs ({@code ItemService.isAutoVanilla}, on the API since wave 5). If the
     * answer is no, a def that looks exactly like an automatic one (id =
     * material, COMMON, no price, no lore, no tags) is treated as one.
     */
    static boolean isAutoVanilla(String id) {
        // Wave 5: ItemService.isAutoVanilla is on the API now (materials request 4); core's impl answers from its
        // own set. Its no-op default is false, so a def that LOOKS exactly automatic still counts as one.
        if (Sky.items().isAutoVanilla(id)) return true;
        ItemDef d = Sky.items().def(id);
        return d != null && id.equals(d.material) && d.npcSell == 0 && (d.lore == null || d.lore.isEmpty())
                && (d.tags == null || d.tags.isEmpty()) && (d.extra == null || d.extra.size() == 0);
    }

    /**
     * Wave 5: research 02 §5.3 at LIVE prices. The generator already clamps every compression against the
     * research prices, but an input another module registers may be priced lower than the research said, which
     * would turn buy-compress-sell back into a coin printer. Only defs materials registered are touched; the
     * original number goes to {@code extra.referencePrice}. Runs to a fixpoint so chains settle bottom-up.
     */
    void applyLiveSellCap() {
        for (int pass = 0; pass < 20; pass++) {
            boolean changed = false;
            for (Recipe r : catalogue.registeredRecipes) {
                if (!catalogue.registered.contains(r.result) || !compressor.isCompression(r)) continue;
                double cap = ChainMath.cappedPrice(r, Compressor::known, MaterialsMenus::priceOf);
                ItemDef d = Sky.items().def(r.result);
                if (cap < 0 || d == null || cap >= d.npcSell - 1e-9) continue;
                // "No input sells, so neither does the result" only when the REFERENCE says the inputs are unsellable:
                // a module that registered an input without a price must not zero the enchanted form of it.
                if (cap == 0 && !inputsUnsellableInData(r)) continue;
                if (d.extra != null && !d.extra.has("referencePrice")) d.extra.addProperty("referencePrice", d.npcSell);
                catalogue.liveCapped.putIfAbsent(r.result, d.npcSell);
                d.npcSell = cap;
                changed = true;
            }
            if (!changed) break;
        }
        if (!catalogue.liveCapped.isEmpty()) {
            Sky.log().info("[materials] live sell cap lowered " + catalogue.liveCapped.size() + " price(s): "
                    + MaterialsSelfTests.head(new ArrayList<>(catalogue.liveCapped.keySet())));
        }
    }

    /** True when the catalogue data (research prices) gives none of the recipe's inputs an NPC price. */
    private boolean inputsUnsellableInData(Recipe r) {
        for (String id : ChainMath.ingredients(r, Compressor::known).keySet()) {
            ItemDef data = catalogue.dataDef(id);
            if (data == null || data.npcSell > 0) return false;
        }
        return true;
    }

    // ---- accessors ------------------------------------------------------------------------------

    MaterialsCatalogue catalogue() { return catalogue; }
    Compressor compressor() { return compressor; }
    Consumables consumables() { return consumables; }
    Dials.Dial<Boolean> dialLiveSellCap() { return liveSellCap; }
    MaterialsMenus menus() { return menus; }
    Dials.Dial<Boolean> dialCompressEnabled() { return compressEnabled; }
    Dials.Dial<Integer> dialMaxPerClick() { return maxPerClick; }
    Dials.Dial<Integer> dialCooldownMs() { return cooldownMs; }
    Dials.Dial<Boolean> dialFromSacks() { return fromSacks; }
    Dials.Dial<Boolean> dialRecipeUnlocks() { return recipeUnlocks; }

    /** Every recipe that makes a catalogue item, live (ours or a specialist's). */
    List<Recipe> liveRecipesFor(String id) { return Compressor.recipesFor(id); }

    void tell(Player p, Compressor.Result r) {
        Msg.send(p, r.message());
        Msg.sound(p, r.ok() ? org.bukkit.Sound.BLOCK_ANVIL_USE : org.bukkit.Sound.ENTITY_VILLAGER_NO, r.ok() ? 1.6f : 1f);
    }

    static List<String> sorted(Set<String> s) { List<String> l = new ArrayList<>(s); l.sort(null); return l; }
}
