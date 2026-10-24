package com.auraqol.skyblock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Item -> price keys -> a quote. Pure; the rules here are the ones the tests pin.
 *
 * <h2>Keys, verified against the live lowest-BIN file on 2026-10-07</h2>
 * The primary lowest-BIN source (hysky.de, Skyblocker's backend) keys almost
 * everything by the item's own SkyBlock id. The exceptions, each of which
 * would otherwise show no price at all:
 * <ul>
 *   <li><b>Enchanted books</b> all share id {@code ENCHANTED_BOOK}. A book with
 *       exactly one enchant is the bazaar product
 *       {@code ENCHANTMENT_<NAME>_<LEVEL>}; a book with several has no single
 *       price and is skipped rather than guessed.</li>
 *   <li><b>Pets</b> all share id {@code PET}; type and rarity are in the
 *       {@code petInfo} JSON string. hysky prices them in level brackets,
 *       {@code LVL_1_LEGENDARY_PHOENIX} and {@code LVL_100_LEGENDARY_PHOENIX}
 *       (plus {@code LVL_200_} for the 200-cap pets), so the tooltip shows
 *       the brackets rather than inventing a price for level 57. The Coflnet
 *       fallback keys them {@code PHOENIX;4} (rarity index). A pet holding a
 *       Tier Boost reports its BOOSTED rarity, so one rarity is taken off -
 *       otherwise a boosted Epic is priced as a real Legendary.</li>
 *   <li><b>Runes</b> share id {@code RUNE}/{@code UNIQUE_RUNE}; hysky uses
 *       {@code ZAP_RUNE_3}, Coflnet {@code RUNE_ZAP_3}.</li>
 *   <li><b>New Year cakes</b>: {@code NEW_YEAR_CAKE_<year>}.</li>
 *   <li><b>Potions</b>: {@code <NAME>_POTION_<level>[_ENHANCED][_EXTENDED][_SPLASH]},
 *       best effort.</li>
 * </ul>
 */
public final class Pricing {

    private Pricing() {
    }

    static final String[] RARITIES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"};

    private static final Pattern PET_LEVEL = Pattern.compile("\\[Lvl (\\d+)]");

    /** A pet's identity, from {@code petInfo}. */
    public record Pet(String type, String tier, String heldItem) {
    }

    /** One lowest-BIN price for a pet at a level bracket. */
    public record PetPrice(int level, double price) {
    }

    /**
     * Everything the tooltip can say about one item. Any field may be absent:
     * {@code bazaar} null, the doubles NaN, {@code petPrices} empty.
     */
    public record Quote(String bazaarId, PriceData.Bazaar bazaar, double lbin,
                        List<PetPrice> petPrices, double npc, int count) {

        public boolean isEmpty() {
            return bazaar == null && Double.isNaN(lbin) && petPrices.isEmpty() && Double.isNaN(npc);
        }
    }

    // ------------------------------------------------------------ keys

    /** The bazaar product id for this item (books get their ENCHANTMENT_ id), or null. */
    public static String bazaarId(SbItem it) {
        if (it == null || it.id() == null) {
            return null;
        }
        if ("ENCHANTED_BOOK".equals(it.id())) {
            if (it.enchantments().size() != 1) {
                return null;
            }
            Map.Entry<String, Integer> e = it.enchantments().entrySet().iterator().next();
            return "ENCHANTMENT_" + e.getKey().toUpperCase(Locale.ROOT) + "_" + e.getValue();
        }
        return it.id();
    }

    /** Parse {@code petInfo}; null if absent or unreadable. Tier Boost is undone here. */
    public static Pet pet(String petInfo) {
        if (petInfo == null || petInfo.isBlank()) {
            return null;
        }
        try {
            JsonObject o = JsonParser.parseString(petInfo).getAsJsonObject();
            String type = o.get("type").getAsString().toUpperCase(Locale.ROOT);
            String tier = o.get("tier").getAsString().toUpperCase(Locale.ROOT);
            String held = o.has("heldItem") && !o.get("heldItem").isJsonNull()
                    ? o.get("heldItem").getAsString() : null;
            if ("PET_ITEM_TIER_BOOST".equals(held)) {
                int idx = rarityIndex(tier);
                if (idx > 0) {
                    tier = RARITIES[idx - 1];
                }
            }
            return new Pet(type, tier, held);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int rarityIndex(String tier) {
        for (int i = 0; i < RARITIES.length; i++) {
            if (RARITIES[i].equals(tier)) {
                return i;
            }
        }
        return -1;
    }

    /** "[Lvl 57] Phoenix" -> 57; 0 if the name carries no level. */
    public static int petLevel(String displayName) {
        if (displayName == null) {
            return 0;
        }
        Matcher m = PET_LEVEL.matcher(displayName);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * Lowest-BIN keys for a non-pet item, best first. Both sources' spellings
     * are listed so whichever one is loaded answers; they never collide.
     */
    public static List<String> lbinKeys(SbItem it) {
        List<String> keys = new ArrayList<>(2);
        if (it == null || it.id() == null) {
            return keys;
        }
        String id = it.id();
        switch (id) {
            case "RUNE", "UNIQUE_RUNE" -> {
                if (it.runes().size() == 1) {
                    Map.Entry<String, Integer> r = it.runes().entrySet().iterator().next();
                    String type = r.getKey().toUpperCase(Locale.ROOT);
                    keys.add(type + "_RUNE_" + r.getValue());
                    keys.add("RUNE_" + type + "_" + r.getValue());
                }
            }
            case "NEW_YEAR_CAKE" -> {
                if (it.cakeYear() > 0) {
                    keys.add("NEW_YEAR_CAKE_" + it.cakeYear());
                }
            }
            case "POTION" -> {
                if (it.potion() != null && it.potionLevel() > 0) {
                    StringBuilder k = new StringBuilder(it.potion().toUpperCase(Locale.ROOT))
                            .append("_POTION_").append(it.potionLevel());
                    if (it.enhanced()) {
                        k.append("_ENHANCED");
                    }
                    if (it.extended()) {
                        k.append("_EXTENDED");
                    }
                    if (it.splash()) {
                        k.append("_SPLASH");
                    }
                    keys.add(k.toString());
                }
            }
            case "PET", "ENCHANTED_BOOK" -> {
                // priced elsewhere (pet brackets / bazaar)
            }
            default -> keys.add(id);
        }
        return keys;
    }

    // ------------------------------------------------------------ quotes

    public static Quote quote(PriceData d, SbItem it) {
        String bzId = bazaarId(it);
        PriceData.Bazaar bz = bzId == null ? null : d.bazaar(bzId);
        double lbin = Double.NaN;
        for (String k : lbinKeys(it)) {
            double v = d.lbin(k);
            if (!Double.isNaN(v)) {
                lbin = v;
                break;
            }
        }
        List<PetPrice> pets = List.of();
        if (it != null && "PET".equals(it.id())) {
            pets = petPrices(d, pet(it.petInfo()));
        }
        double npc = it == null ? Double.NaN : d.npc(it.id());
        return new Quote(bzId, bz, lbin, pets, npc, it == null ? 1 : it.count());
    }

    /**
     * Lowest BIN at each bracket hysky publishes (1, 100, 200), or the single
     * Coflnet {@code TYPE;rarity} price reported as level 0 when only the
     * fallback is loaded.
     */
    public static List<PetPrice> petPrices(PriceData d, Pet p) {
        List<PetPrice> out = new ArrayList<>(3);
        if (p == null) {
            return out;
        }
        for (int lvl : new int[] {1, 100, 200}) {
            double v = d.lbin("LVL_" + lvl + "_" + p.tier() + "_" + p.type());
            if (!Double.isNaN(v)) {
                out.add(new PetPrice(lvl, v));
            }
        }
        if (out.isEmpty()) {
            int idx = rarityIndex(p.tier());
            if (idx >= 0) {
                double v = d.lbin(p.type() + ";" + idx);
                if (!Double.isNaN(v)) {
                    out.add(new PetPrice(0, v));
                }
            }
        }
        return out;
    }

    /**
     * What one unit is worth for income tracking: bazaar insta-sell (what you
     * could get right now), else lowest BIN, else NPC. NaN if nothing knows.
     *
     * <p>A pet takes the bracket at or below its level - a level 57 pet is
     * worth a level 1 pet's BIN as a floor, never the level 100 price.
     */
    public static double unitValue(PriceData d, SbItem it) {
        Quote q = quote(d, it);
        if (q.bazaar() != null && q.bazaar().instasell() > 0) {
            return q.bazaar().instasell();
        }
        if (!q.petPrices().isEmpty()) {
            int lvl = petLevel(it.displayName());
            double best = Double.NaN;
            for (PetPrice pp : q.petPrices()) {
                if (pp.level() <= Math.max(1, lvl) || pp.level() == 0) {
                    best = pp.price();
                }
            }
            return Double.isNaN(best) ? q.petPrices().get(0).price() : best;
        }
        if (!Double.isNaN(q.lbin())) {
            return q.lbin();
        }
        return q.npc();
    }

    /**
     * The identity two stacks must share to be "the same thing" in an
     * inventory diff. A pet's rarity and type are part of it, a book's enchant
     * is, a stack's lore is not.
     */
    public static String valueKey(SbItem it) {
        if (it == null || it.id() == null) {
            return null;
        }
        String bz = bazaarId(it);
        if ("ENCHANTED_BOOK".equals(it.id())) {
            return bz == null ? "ENCHANTED_BOOK:" + it.enchantments() : bz;
        }
        if ("PET".equals(it.id())) {
            Pet p = pet(it.petInfo());
            return p == null ? "PET" : "PET:" + p.type() + ":" + p.tier() + ":" + petLevel(it.displayName());
        }
        List<String> k = lbinKeys(it);
        return k.isEmpty() ? it.id() : k.get(0);
    }
}
