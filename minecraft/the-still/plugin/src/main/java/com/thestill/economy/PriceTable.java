package com.thestill.economy;

import com.thestill.core.AtomicFiles;
import com.thestill.core.Service;
import com.thestill.item.ItemTags;
import com.thestill.mine.DepthPlan;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What things are worth.
 *
 * <h2>The rule that keeps items alive</h2>
 * A price lookup is <b>by tag first, and a tagged item never falls back to its
 * material price</b>. Shadow Prison's {@code /sellall} matched on
 * {@link Material} alone, so a custom item made of a priced material was
 * vaporised for the value of its base block — a review finding, and the kind
 * that costs a player something they spent a week earning.
 *
 * <p>Anything carrying meaningful item meta — a name, lore, enchantments — is
 * also refused, even untagged. That is a blunt guard rather than an identity
 * check (identity is always the PDC tag), but it means a renamed keepsake in a
 * pile of cobblestone survives contact with {@code /sellall}.
 */
public final class PriceTable implements Service {

    private final Logger log;
    private final File file;
    private final ItemTags tags;

    private final Map<Material, Long> byMaterial = new EnumMap<>(Material.class);
    private final Map<String, Long> byTag = new HashMap<>();

    public PriceTable(Logger log, File file, ItemTags tags) {
        this.log = log;
        this.file = file;
        this.tags = tags;
    }

    @Override
    public void start() {
        load();
        auditCraftPrinters();
    }

    // ---- lookup ---------------------------------------------------------

    /**
     * What one of these is worth, in minor units.
     *
     * @return the unit price, or null if this item is not sellable — which is
     *         the safe answer, because "not sellable" leaves the item in the
     *         player's inventory and "sellable for the wrong price" does not
     */
    public Long unitPrice(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        String kind = tags.kindOf(stack);
        if (kind != null) {
            String id = tags.idOf(stack);
            // Deliberately no fallback to the material price.
            return byTag.get(tagKey(kind, id));
        }
        if (hasMeaningfulMeta(stack)) {
            return null;
        }
        return byMaterial.get(stack.getType());
    }

    /** Total value of a whole stack. */
    public long stackValue(ItemStack stack) {
        Long unit = unitPrice(stack);
        if (unit == null) {
            return 0L;
        }
        try {
            return Math.multiplyExact(unit, stack.getAmount());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public boolean isSellable(ItemStack stack) {
        return unitPrice(stack) != null;
    }

    /**
     * True if this stack is more than a plain block, and therefore must not be
     * sold at its material's price.
     */
    private static boolean hasMeaningfulMeta(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return false;
        }
        var meta = stack.getItemMeta();
        // Name, lore and enchantments only. Custom model data is deliberately
        // not checked: its accessor is deprecated and moving, and a resource-pack
        // model is a cosmetic detail rather than evidence the item matters.
        return meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants();
    }

    private static String tagKey(String kind, String id) {
        return kind + ":" + (id == null ? "" : id);
    }

    // ---- editing --------------------------------------------------------

    public void setMaterialPrice(Material material, long minorUnits) {
        if (minorUnits <= 0) {
            byMaterial.remove(material);
        } else {
            byMaterial.put(material, minorUnits);
        }
    }

    public void setTagPrice(String kind, String id, long minorUnits) {
        if (minorUnits <= 0) {
            byTag.remove(tagKey(kind, id));
        } else {
            byTag.put(tagKey(kind, id), minorUnits);
        }
    }

    public Long materialPrice(Material material) {
        return byMaterial.get(material);
    }

    public Map<Material, Long> allMaterialPrices() {
        return Map.copyOf(byMaterial);
    }

    public int size() {
        return byMaterial.size() + byTag.size();
    }

    // ---- persistence ----------------------------------------------------

    public void load() {
        byMaterial.clear();
        byTag.clear();
        if (!file.isFile()) {
            log.info("no prices.yml yet — writing defaults");
            applyDefaults();
            save();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection materials = yaml.getConfigurationSection("materials");
        if (materials != null) {
            for (String key : materials.getKeys(false)) {
                // Per-row try/catch: one unknown material in a hand-edited file
                // must not cost the server every other price it knows.
                try {
                    Material material = Material.matchMaterial(key);
                    if (material == null) {
                        log.warning("prices.yml: unknown material '" + key + "'; skipping");
                        continue;
                    }
                    double units = materials.getDouble(key, -1);
                    if (!Double.isFinite(units) || units <= 0) {
                        log.warning("prices.yml: bad price for " + key + "; skipping");
                        continue;
                    }
                    byMaterial.put(material, Money.fromUnits(units));
                } catch (RuntimeException e) {
                    log.warning("prices.yml: could not read '" + key + "' (" + e.getMessage() + ")");
                }
            }
        }

        ConfigurationSection custom = yaml.getConfigurationSection("custom");
        if (custom != null) {
            for (String key : custom.getKeys(false)) {
                try {
                    double units = custom.getDouble(key, -1);
                    if (Double.isFinite(units) && units > 0) {
                        byTag.put(key, Money.fromUnits(units));
                    }
                } catch (RuntimeException e) {
                    log.warning("prices.yml: could not read custom '" + key + "'");
                }
            }
        }
        log.info("loaded " + byMaterial.size() + " material price(s) and "
                + byTag.size() + " custom price(s)");
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        byMaterial.forEach((material, minor) ->
                yaml.set("materials." + material.name(), Money.toUnits(minor)));
        byTag.forEach((key, minor) -> yaml.set("custom." + key, Money.toUnits(minor)));
        yaml.options().setHeader(List.of(
                "The Still — sell prices, in dollars per item.",
                "",
                "materials:  plain blocks and items, matched by Material.",
                "custom:     tagged items, keyed \"kind:id\". A TAGGED ITEM IS ONLY",
                "            SELLABLE IF IT APPEARS HERE — it never falls back to",
                "            its material's price, so a custom item can never be",
                "            vaporised by /sellall for the value of its base block."));
        try {
            AtomicFiles.writeString(file, yaml.saveToString());
        } catch (IOException e) {
            log.log(Level.SEVERE, "could not write prices.yml", e);
        }
    }

    /**
     * Starting prices, carried over from Shadow Prison's live table rather than
     * invented. Ore values are its retuned 2026 numbers; filler follows the same
     * flat-then-step ramp, keyed off {@link DepthPlan}.
     */
    private void applyDefaults() {
        byMaterial.putAll(defaultPrices());
    }

    /**
     * The starting price list, as pure data so tests can check the progression
     * without needing a running server.
     */
    public static Map<Material, Long> defaultPrices() {
        Map<Material, Long> prices = new EnumMap<>(Material.class);

        // Filler, one per depth. Pulled from DepthPlan so the block a mine places
        // and the price it sells for cannot drift apart.
        for (int depth = 1; depth <= DepthPlan.DEPTH_COUNT; depth++) {
            prices.put(DepthPlan.fillerFor(depth),
                    Money.fromUnits(DepthPlan.fillerPriceFor(depth)));
        }

        // Ore drops. Note the multi-drop ores: one copper ore yields 2-5 raw
        // copper, redstone 4-5, lapis 4-9. Their per-ITEM price is what is set
        // here, so their per-BLOCK value is several times these numbers — which
        // is why their weights in DepthPlan are low. Move one without the other
        // and a mid depth becomes the best in the game.
        prices.put(Material.COAL, Money.fromUnits(6));
        prices.put(Material.RAW_IRON, Money.fromUnits(15));
        prices.put(Material.RAW_COPPER, Money.fromUnits(8));
        prices.put(Material.REDSTONE, Money.fromUnits(9));
        prices.put(Material.RAW_GOLD, Money.fromUnits(20));
        prices.put(Material.LAPIS_LAZULI, Money.fromUnits(14));
        prices.put(Material.EMERALD, Money.fromUnits(60));
        prices.put(Material.QUARTZ, Money.fromUnits(30));

        // Condensed forms, priced at exactly the craft ratio. Anything else is a
        // money printer; see findCraftPrinters.
        prices.put(Material.COAL_BLOCK, Money.fromUnits(6 * 9));
        prices.put(Material.RAW_IRON_BLOCK, Money.fromUnits(15 * 9));
        prices.put(Material.RAW_COPPER_BLOCK, Money.fromUnits(8 * 9));
        prices.put(Material.REDSTONE_BLOCK, Money.fromUnits(9 * 9));
        prices.put(Material.RAW_GOLD_BLOCK, Money.fromUnits(20 * 9));
        prices.put(Material.LAPIS_BLOCK, Money.fromUnits(14 * 9));
        prices.put(Material.EMERALD_BLOCK, Money.fromUnits(60 * 9));

        // Smelted forms. Ingots are worth more than raw, or smelting is pointless.
        prices.put(Material.IRON_INGOT, Money.fromUnits(42));
        prices.put(Material.GOLD_INGOT, Money.fromUnits(60));
        prices.put(Material.COPPER_INGOT, Money.fromUnits(24));
        prices.put(Material.IRON_BLOCK, Money.fromUnits(42 * 9));
        prices.put(Material.GOLD_BLOCK, Money.fromUnits(60 * 9));
        prices.put(Material.COPPER_BLOCK, Money.fromUnits(24 * 9));
        return prices;
    }

    /** Static form of {@link #findCraftPrinters()}, for testing a price map directly. */
    public static List<String> findCraftPrinters(Map<Material, Long> prices) {
        List<String> problems = new ArrayList<>();
        for (CraftRule rule : CRAFT_RULES) {
            Long unit = prices.get(rule.unit());
            Long result = prices.get(rule.result());
            if (unit == null || result == null) {
                continue;
            }
            long inputs = unit * rule.count();
            if (result > inputs) {
                problems.add(rule.count() + "x " + rule.unit() + " (" + Money.format(inputs)
                        + ") crafts into " + rule.result() + " worth " + Money.format(result)
                        + " — a money printer worth " + Money.format(result - inputs)
                        + " per craft");
            }
        }
        return problems;
    }

    // ---- the money-printer guard ----------------------------------------

    /**
     * A bulk crafting relationship: {@code count} x {@code unit} makes one
     * {@code result}.
     */
    public record CraftRule(Material unit, int count, Material result) {
    }

    /**
     * Every recipe that turns something priced into something else priced.
     *
     * <p>Not exhaustive over Minecraft — exhaustive over <em>this price table</em>,
     * which is the only place it matters. Add a row whenever you price a new
     * material that something cheap crafts into.
     */
    private static final List<CraftRule> CRAFT_RULES = List.of(
            new CraftRule(Material.COAL, 9, Material.COAL_BLOCK),
            new CraftRule(Material.RAW_IRON, 9, Material.RAW_IRON_BLOCK),
            new CraftRule(Material.RAW_COPPER, 9, Material.RAW_COPPER_BLOCK),
            new CraftRule(Material.RAW_GOLD, 9, Material.RAW_GOLD_BLOCK),
            new CraftRule(Material.REDSTONE, 9, Material.REDSTONE_BLOCK),
            new CraftRule(Material.LAPIS_LAZULI, 9, Material.LAPIS_BLOCK),
            new CraftRule(Material.EMERALD, 9, Material.EMERALD_BLOCK),
            new CraftRule(Material.DIAMOND, 9, Material.DIAMOND_BLOCK),
            new CraftRule(Material.IRON_INGOT, 9, Material.IRON_BLOCK),
            new CraftRule(Material.GOLD_INGOT, 9, Material.GOLD_BLOCK),
            new CraftRule(Material.COPPER_INGOT, 9, Material.COPPER_BLOCK),
            // Not nine. Getting the ratio wrong is the same bug as getting the
            // price wrong, and these two are the easy ones to assume.
            new CraftRule(Material.QUARTZ, 4, Material.QUARTZ_BLOCK),
            new CraftRule(Material.AMETHYST_SHARD, 4, Material.AMETHYST_BLOCK),
            // Filler variants. Cheap in, expensive out is the classic shape.
            new CraftRule(Material.TUFF, 4, Material.POLISHED_TUFF),
            new CraftRule(Material.POLISHED_TUFF, 4, Material.TUFF_BRICKS),
            new CraftRule(Material.COBBLED_DEEPSLATE, 4, Material.POLISHED_DEEPSLATE),
            new CraftRule(Material.POLISHED_DEEPSLATE, 4, Material.DEEPSLATE_BRICKS),
            new CraftRule(Material.DEEPSLATE_BRICKS, 4, Material.DEEPSLATE_TILES),
            new CraftRule(Material.BLACKSTONE, 4, Material.POLISHED_BLACKSTONE),
            new CraftRule(Material.POLISHED_BLACKSTONE, 4, Material.POLISHED_BLACKSTONE_BRICKS),
            new CraftRule(Material.BASALT, 4, Material.POLISHED_BASALT),
            new CraftRule(Material.END_STONE, 4, Material.END_STONE_BRICKS),
            new CraftRule(Material.STONE, 4, Material.STONE_BRICKS));

    /**
     * Finds prices that let a player craft their way to free money.
     *
     * <p>Shadow Prison shipped one of these live: {@code compact_emerald} was
     * priced 8100 against a correct 9 x 540 = 4860, so nine emeralds bought a
     * block worth 3240 more than the emeralds. Somebody found it, and then
     * everybody was doing it.
     *
     * <p>Only the forward direction is a genuine exploit: a result worth more
     * than its inputs mints money out of a crafting table. The reverse merely
     * means condensing is a bad idea, which is allowed.
     *
     * @return one human-readable line per violation, empty when the table is safe
     */
    public List<String> findCraftPrinters() {
        return findCraftPrinters(byMaterial);
    }

    /** Logs any money printers found. Called at startup, before players can reach them. */
    public void auditCraftPrinters() {
        List<String> problems = findCraftPrinters();
        if (problems.isEmpty()) {
            log.info("price table audit: no craft exploits");
            return;
        }
        log.severe("PRICE TABLE HAS " + problems.size() + " MONEY PRINTER(S):");
        problems.forEach(problem -> log.severe("  " + problem));
        log.severe("Fix prices.yml before letting anyone play, or the economy is over.");
    }
}
