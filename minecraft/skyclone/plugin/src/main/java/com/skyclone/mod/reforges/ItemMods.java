package com.skyclone.mod.reforges;

import com.google.gson.JsonElement;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.stat.Stat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Pure item-modifier math: the stats an instance's stars, gems, Art of War/Peace, enrichment and
 * weapon-damage reforges add (one {@code ItemStatContributor}), and what combining an item with an
 * upgrade item in the anvil would do. No Bukkit: unit-tested against the research numbers.
 */
public final class ItemMods {

    private final ReforgeData data;

    public ItemMods(ReforgeData data) { this.data = data; }

    // ---- item instance stats -------------------------------------------------------------------

    public void contribute(ItemDef def, ItemData d, Rarity rarity, ItemStatSheet sheet) {
        if (def == null || d == null) return;
        // weapon-damage part of a reforge (Suspicious, Warped, Gilded, Beady): not a player stat
        if (d.reforge != null) {
            ReforgeData.Row row = data.rows.get(d.reforge);
            if (row != null) {
                Double dmg = ReforgeData.statsFor(row, rarity).get("DAMAGE");
                if (dmg != null && dmg != 0) sheet.addDamage(dmg, true);
            }
        }
        // stars: +2% of BASE stats per essence star (research 01 §16.1)
        int stars = data.statStars(def, d.stars);
        if (stars > 0) {
            double f = ReforgeData.STAR_PCT * stars;
            if (def.stats != null) for (Map.Entry<Stat, Double> e : def.stats.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() != 0) {
                    sheet.add(e.getKey(), round(e.getValue() * f), ItemStatSheet.Source.STARS);
                }
            }
            if (def.damage != 0) sheet.addDamage(round(def.damage * f), true);
        }
        // gems
        if (d.gems != null && !d.gems.isEmpty()) {
            List<String> types = data.slotTypes(def);
            for (Map.Entry<String, String> e : d.gems.entrySet()) {
                String[] qt = ReforgeData.parseGem(e.getValue());
                if (qt == null) continue;
                int idx;
                try { idx = Integer.parseInt(e.getKey()); } catch (NumberFormatException ex) { continue; }
                String slotType = idx >= 0 && idx < types.size() ? types.get(idx) : null;
                Stat st = data.gemStat(qt[1]);
                if (st == null) continue;
                double v = data.gemValue(e.getValue(), rarity, slotType);
                if (v != 0) sheet.add(st, v, ItemStatSheet.Source.GEMSTONE);
            }
        }
        // one-off books
        if (flag(d, "art_of_war")) sheet.add(Stat.STRENGTH, ReforgeData.ART_OF_WAR_STRENGTH, ItemStatSheet.Source.OTHER);
        if (flag(d, "art_of_peace")) sheet.add(Stat.HEALTH, ReforgeData.ART_OF_PEACE_HEALTH, ItemStatSheet.Source.OTHER);
        int polar = count(d, "polarvoid");
        if (polar > 0) {
            sheet.add(Stat.MINING_SPEED, POLARVOID_SPEED * polar, ItemStatSheet.Source.OTHER);
            sheet.add(Stat.MINING_FORTUNE, POLARVOID_FORTUNE, ItemStatSheet.Source.OTHER);
        }
        if (flag(d, "wood_singularity_count")) sheet.add(Stat.FORAGING_FORTUNE, WOOD_SINGULARITY_FORTUNE, ItemStatSheet.Source.OTHER);
        // vacuums (wave 5): farming's own contributor adds Farmer's Almanac fortune to ITS tools only, so the
        // vacuum half of the reference "farming tool or vacuum" is added here; Bookworm's Favorite Book is +10 damage each
        if (def.category == ItemCategory.VACUUM) {
            int alm = count(d, ALMANAC_KEY);
            if (alm > 0) sheet.add(Stat.FARMING_FORTUNE, alm, ItemStatSheet.Source.OTHER);
            int worm = count(d, BOOKWORM_KEY);
            if (worm > 0) sheet.addDamage(BOOKWORM_DAMAGE * Math.min(BOOKWORM_MAX, worm), true);
        }
        // accessory enrichment
        String en = str(d, "enrichment");
        if (en != null) {
            ReforgeData.Enrich e = data.enrichments.get(en);
            Stat st = e == null ? null : Stat.parse(e.stat);
            if (st != null) sheet.add(st, e.value, ItemStatSheet.Source.OTHER);
        }
    }

    static double round(double v) { return Math.round(v * 10.0) / 10.0; }

    /** Reference item lore: Polarvoid Book +10 Mining Speed each (max 5) and +5 Mining Fortune once one is in. */
    public static final double POLARVOID_SPEED = 10, POLARVOID_FORTUNE = 5;
    public static final int POLARVOID_MAX = 5;
    /** Reference item lore: Mana Disintegrator -1% ability mana cost each, max 10. */
    public static final int MANA_DISINTEGRATOR_MAX = 10;
    /** Reference item lore: Wood Singularity grants an axe +25 Foraging Fortune. */
    public static final double WOOD_SINGULARITY_FORTUNE = 25;

    /** Transmission Tuner (research 01 sec 19): +1 teleport block each on the two teleport blades, max 4; weapons reads the key. */
    public static final int TUNER_MAX = 4;
    public static final java.util.Set<String> TUNABLE = java.util.Set.of("ASPECT_OF_THE_END", "ASPECT_OF_THE_VOID");
    /** Etherwarp Merger: binds the sneak-click warp to the void blade only (research 06); weapons reads {@code ethermerge}. */
    public static final java.util.Set<String> MERGEABLE = java.util.Set.of("ASPECT_OF_THE_VOID");
    /** Bookworm's Favorite Book (NEU lore): a vacuum gains +10 damage each, up to 5. */
    public static final double BOOKWORM_DAMAGE = 10;
    public static final int BOOKWORM_MAX = 5;
    /** Silex (NEU lore): a pickaxe or drill at Efficiency V..IX gains one Efficiency level, up to X. */
    public static final int SILEX_FROM = 5, SILEX_MAX = 10;
    /** Reference ItemData keys this anvil writes (weapons / farming / core read the same keys). */
    public static final String ALMANAC_KEY = "farm_almanac", BOOKWORM_KEY = "bookworm_books", SCROLL_KEY = "power_ability_scroll",
            TUNER_KEY = "tuned_transmission", MERGER_KEY = "ethermerge", DISINTEGRATOR_KEY = "mana_disintegrator_count";
    /** Every extra key an anvil upgrade writes (the admin {@code clear} strips them all). */
    public static final List<String> ANVIL_KEYS = List.of("art_of_war", "art_of_peace", "enrichment", "polarvoid", "stats_book",
            "wood_singularity_count", DISINTEGRATOR_KEY, TUNER_KEY, MERGER_KEY, ALMANAC_KEY, BOOKWORM_KEY, SCROLL_KEY, "chomp_slain");
    /** Farmer's Almanac cap: farming's dial {@code farming.almanac-max} (the module wires it; 5 = research 08 1.4). */
    public static java.util.function.IntSupplier almanacMax = () -> 5;

    public static int count(ItemData d, String key) {
        if (d.extra == null || !d.extra.has(key)) return 0;
        try { return d.extra.get(key).getAsInt(); } catch (Exception ex) { return 0; }
    }

    /** Book of Stats: kills on weapons, crops on farming tools. */
    public static boolean statsBookTarget(ItemDef def) {
        return def != null && (weapon(def) || farmingTool(def));
    }

    public static boolean farmingTool(ItemDef def) {
        return def != null && (def.category == ItemCategory.HOE || def.has("farming_tool"));
    }

    public static boolean flag(ItemData d, String key) {
        if (d.extra == null || !d.extra.has(key)) return false;
        JsonElement e = d.extra.get(key);
        try { return e.isJsonPrimitive() && (e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : e.getAsInt() > 0); }
        catch (Exception ex) { return false; }
    }

    public static String str(ItemData d, String key) {
        if (d.extra == null || !d.extra.has(key)) return null;
        JsonElement e = d.extra.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : null;
    }

    // ---- anvil combinations ----------------------------------------------------------------------

    /** The outcome of putting {@code mod} next to an item in the anvil. {@code error} non-null = refused. */
    public record Plan(String error, double coins, String verb, Consumer<ItemData> change, String reforgeId) {
        static Plan no(String why) { return new Plan(why, 0, null, null, null); }
        public boolean ok() { return error == null; }
    }

    /** Has a RIGHT CLICK item ability (what a power scroll binds to). */
    public static boolean rightClickAbility(ItemDef def) {
        if (def == null || def.abilities == null) return false;
        for (com.skyclone.api.item.AbilityDef a : def.abilities)
            if (a != null && a.trigger == com.skyclone.api.item.AbilityDef.Trigger.RIGHT_CLICK) return true;
        return false;
    }

    static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    public static boolean potatoTarget(ItemDef def) {
        ItemCategory c = def.category;
        return c != null && (c.isMeleeWeapon() || c == ItemCategory.BOW || c.isArmor());
    }

    public static boolean weapon(ItemDef def) {
        ItemCategory c = def.category;
        return c != null && (c.isMeleeWeapon() || c == ItemCategory.BOW);
    }

    /**
     * @param def the item being upgraded, {@code d} its instance data, {@code eff} its effective rarity
     * @param mod the def of the thing being combined into it
     */
    public Plan plan(ItemDef def, ItemData d, Rarity eff, ItemDef mod) {
        if (def == null || d == null) return Plan.no("Put an item in the left slot.");
        if (mod == null) return Plan.no("Put a reforge stone or an upgrade in the right slot.");
        if (!def.isUnique()) return Plan.no("Only gear can be upgraded.");
        // reforge stone
        ReforgeData.Row row = data.byStone.get(mod.id);
        if (row != null) {
            if (!data.appliesTo(row, def)) return Plan.no("That stone does not fit this kind of item.");
            if (!ReforgeData.rarityAllowed(row, eff)) return Plan.no("That reforge cannot go on a " + eff.display() + " item.");
            double cost = ReforgeData.stoneCost(row, eff);
            String id = row.id;
            return new Plan(null, cost, "Reforge into &9" + row.name, x -> x.reforge = id, id);
        }
        switch (mod.id) {
            case "HOT_POTATO_BOOK" -> {
                if (!potatoTarget(def)) return Plan.no("Potato tomes only bind to weapons and armor.");
                if (d.potatoBooks >= ReforgeData.HOT_POTATO_MAX) return Plan.no("This item already holds " + ReforgeData.HOT_POTATO_MAX + " roasted tomes.");
                return new Plan(null, 0, "Bind a &5Roasted Potato Tome", x -> x.potatoBooks = Math.min(ReforgeData.HOT_POTATO_MAX, x.potatoBooks + 1), null);
            }
            case "FUMING_POTATO_BOOK" -> {
                if (!potatoTarget(def)) return Plan.no("Potato tomes only bind to weapons and armor.");
                if (d.potatoBooks < ReforgeData.HOT_POTATO_MAX) return Plan.no("Bind " + ReforgeData.HOT_POTATO_MAX + " roasted tomes first.");
                if (d.potatoBooks >= ReforgeData.POTATO_MAX) return Plan.no("This item is full of potato tomes.");
                return new Plan(null, 0, "Bind a &5Smoking Potato Tome", x -> x.potatoBooks = Math.min(ReforgeData.POTATO_MAX, x.potatoBooks + 1), null);
            }
            case "RECOMBOBULATOR_3000" -> {
                if (d.recombobulated) return Plan.no("This item has already been reshaped.");
                if (def.category == ItemCategory.PET || def.category == ItemCategory.PET_ITEM || def.has("no_recomb"))
                    return Plan.no("This item cannot be reshaped.");
                Rarity base = def.rarity == null ? Rarity.COMMON : def.rarity;
                if (base.upgrade() == base) return Plan.no("This item cannot go any higher.");
                return new Plan(null, 0, "Raise rarity to " + base.upgrade().code() + base.upgrade().display(), x -> x.recombobulated = true, null);
            }
            case "THE_ART_OF_WAR" -> {
                if (!weapon(def)) return Plan.no("The Art of War only teaches weapons.");
                if (flag(d, "art_of_war")) return Plan.no("This weapon has already studied it.");
                return new Plan(null, 0, "Teach &c+5❁ Strength", x -> x.extra.addProperty("art_of_war", 1), null);
            }
            case "THE_ART_OF_PEACE" -> {
                if (def.category == null || !def.category.isArmor()) return Plan.no("The Art of Peace only teaches armor.");
                if (flag(d, "art_of_peace")) return Plan.no("This armor has already studied it.");
                return new Plan(null, 0, "Teach &a+40❤ Health", x -> x.extra.addProperty("art_of_peace", 1), null);
            }
            case "POLARVOID_BOOK" -> {
                if (def.category != ItemCategory.DRILL) return Plan.no("Polarvoid Books only bind to drills.");
                int n = count(d, "polarvoid");
                if (n >= POLARVOID_MAX) return Plan.no("This drill already holds " + POLARVOID_MAX + " Polarvoid Books.");
                return new Plan(null, 0, "Bind a &9Polarvoid Book &7(" + (n + 1) + "/" + POLARVOID_MAX + ")",
                        x -> x.extra.addProperty("polarvoid", Math.min(POLARVOID_MAX, count(x, "polarvoid") + 1)), null);
            }
            case "BOOK_OF_STATS" -> {
                if (!statsBookTarget(def)) return Plan.no("The Book of Stats only binds to weapons and farming tools.");
                if (d.extra != null && d.extra.has("stats_book")) return Plan.no("This item already tracks its stats.");
                return new Plan(null, 0, weapon(def) ? "Start counting &akills" : "Start counting &acrops",
                        x -> x.extra.addProperty("stats_book", 0), null);
            }
            case "MANA_DISINTEGRATOR" -> {
                // reforges request 11 (wave 3): research 01 sec 19, -1% mana cost each, max 10
                boolean manaAbility = false;
                for (com.skyclone.api.item.AbilityDef ab : def.abilities) if (ab != null && ab.mana > 0) manaAbility = true;
                if (!manaAbility) return Plan.no("Mana Disintegrators only fit items with a mana-costing ability.");
                int n = count(d, "mana_disintegrator_count");
                if (n >= MANA_DISINTEGRATOR_MAX) return Plan.no("This item already holds " + MANA_DISINTEGRATOR_MAX + " Mana Disintegrators.");
                return new Plan(null, 0, "Apply a &9Mana Disintegrator &7(" + (n + 1) + "/" + MANA_DISINTEGRATOR_MAX + ")",
                        x -> x.extra.addProperty("mana_disintegrator_count", Math.min(MANA_DISINTEGRATOR_MAX, count(x, "mana_disintegrator_count") + 1)), null);
            }
            case "WOOD_SINGULARITY" -> {
                if (def.category != ItemCategory.AXE) return Plan.no("A Wood Singularity only fuses into an axe.");
                if (flag(d, "wood_singularity_count")) return Plan.no("This axe already holds a Wood Singularity.");
                return new Plan(null, 0, "Fuse for &6+25☘ Foraging Fortune", x -> x.extra.addProperty("wood_singularity_count", 1), null);
            }
            case "TRANSMISSION_TUNER" -> {
                if (!TUNABLE.contains(def.id)) return Plan.no("Transmission Tuners only fit the two teleport blades.");
                int n = count(d, TUNER_KEY);
                if (n >= TUNER_MAX) return Plan.no("This blade already holds " + TUNER_MAX + " Transmission Tuners.");
                return new Plan(null, 0, "Tune: &a+1 teleport block &7(" + (n + 1) + "/" + TUNER_MAX + ")",
                        x -> x.extra.addProperty(TUNER_KEY, Math.min(TUNER_MAX, count(x, TUNER_KEY) + 1)), null);
            }
            case "ETHERWARP_MERGER" -> {
                if (!MERGEABLE.contains(def.id)) return Plan.no("A Phasewarp Merger only binds to the void teleport blade.");
                if (flag(d, MERGER_KEY)) return Plan.no("This blade is already merged.");
                return new Plan(null, 0, "Merge &5Phasewarp &7(sneak + right-click)", x -> x.extra.addProperty(MERGER_KEY, 1), null);
            }
            case "FARMING_FOR_DUMMIES" -> {
                boolean farmTool = def.extra != null && def.extra.has("farmTool") && def.extra.get("farmTool").isJsonObject();
                if (!farmTool && def.category != ItemCategory.VACUUM) return Plan.no("A Farmer's Almanac only binds to a farming tool or a vacuum.");
                int n = count(d, ALMANAC_KEY), max = Math.max(1, almanacMax.getAsInt());
                if (n >= max) return Plan.no("This tool already holds " + max + " almanacs.");
                return new Plan(null, 0, "Bind an almanac: &6+1☘ Farming Fortune &7(" + (n + 1) + "/" + max + ")",
                        x -> x.extra.addProperty(ALMANAC_KEY, Math.min(max, count(x, ALMANAC_KEY) + 1)), null);
            }
            case "BOOKWORM_BOOK" -> {
                if (def.category != ItemCategory.VACUUM) return Plan.no("That book only binds to a vacuum.");
                int n = count(d, BOOKWORM_KEY);
                if (n >= BOOKWORM_MAX) return Plan.no("This vacuum already holds " + BOOKWORM_MAX + " of them.");
                return new Plan(null, 0, "Bind: &c+10 vacuum damage &7(" + (n + 1) + "/" + BOOKWORM_MAX + ")",
                        x -> x.extra.addProperty(BOOKWORM_KEY, Math.min(BOOKWORM_MAX, count(x, BOOKWORM_KEY) + 1)), null);
            }
            case "SIL_EX" -> {
                if (def.category != ItemCategory.PICKAXE && def.category != ItemCategory.DRILL) return Plan.no("Silex only binds to a pickaxe or a drill.");
                int lvl = d.enchant("efficiency");
                if (lvl < SILEX_FROM) return Plan.no("The tool needs Efficiency V first.");
                if (lvl >= SILEX_MAX) return Plan.no("This tool is already at Efficiency X.");
                return new Plan(null, 0, "Raise to &9Efficiency " + roman(lvl + 1),
                        x -> x.enchants.put("efficiency", Math.min(SILEX_MAX, x.enchant("efficiency") + 1)), null);
            }
            default -> { }
        }
        if (mod.extra != null && mod.extra.has("power_scroll")) {
            if (!rightClickAbility(def)) return Plan.no("Power scrolls only bind to an item with a RIGHT CLICK ability.");
            if (mod.id.equals(str(d, SCROLL_KEY))) return Plan.no("This item already holds that scroll.");
            String id = mod.id;
            String verb = str(d, SCROLL_KEY) == null ? "Bind the &5" + mod.name : "Replace the scroll with the &5" + mod.name;
            return new Plan(null, 0, verb, x -> x.extra.addProperty(SCROLL_KEY, id), null);
        }
        if (mod.extra != null && mod.extra.has("master_star")) {
            int n = mod.extra.get("master_star").getAsInt();
            if (!data.masterStarsAllowed(def)) return Plan.no("Master stars only fit dungeon gear.");
            if (d.stars < 5) return Plan.no("Star this item to &6✪✪✪✪✪ &cfirst.");
            if (d.stars >= 10) return Plan.no("This item already has every master star.");
            if (d.stars != 4 + n) return Plan.no("This item needs master star #" + (d.stars - 4) + " next.");
            return new Plan(null, 0, "Add master star &c#" + n, x -> x.stars = 5 + n, null);
        }
        if (mod.extra != null && mod.extra.has("enrichment")) {
            String en = mod.extra.get("enrichment").getAsString();
            if (def.category != ItemCategory.ACCESSORY) return Plan.no("Enrichments only fuse into accessories.");
            if (eff.ordinal() < Rarity.LEGENDARY.ordinal() || eff == Rarity.ULTIMATE || eff == Rarity.ADMIN)
                return Plan.no("Only Legendary or rarer accessories can be enriched.");
            if (en.equals(str(d, "enrichment"))) return Plan.no("This accessory already has that enrichment.");
            ReforgeData.Enrich e = data.enrichments.get(en);
            String label = e == null ? en : e.label;
            return new Plan(null, 0, "Enrich with &a" + label, x -> x.extra.addProperty("enrichment", en), null);
        }
        return Plan.no("That cannot be combined with this item.");
    }

    /** Every star-cost line for the next essence star, or null when the item cannot take one. */
    public List<ReforgeData.Cost> nextStarCost(ItemDef def, ItemData d) {
        ReforgeData.StarEntry e = def == null ? null : data.stars.get(def.id);
        if (e == null) return null;
        int next = d.stars + 1;
        if (next > e.costs.size()) return null;
        List<ReforgeData.Cost> c = e.costs.get(next - 1);
        return c == null ? new ArrayList<>() : c;
    }
}
