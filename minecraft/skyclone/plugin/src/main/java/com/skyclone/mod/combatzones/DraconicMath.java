package com.skyclone.mod.combatzones;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The numbers behind Keeper Ixo's two counters on the Void Ledge (the reference's Tyzzo on the Void Slate):
 * the <b>Draconic Altar</b>, which turns dragon loot into Dragon Essence (with a chance of bonus loot), and the
 * <b>Dragon Essence shop</b>, nine permanent perks. Pure Java (no Bukkit), so unit tests run the shipping numbers.
 *
 * <p>Sources: perk ids, level counts and essence costs are NEU {@code constants/essenceshops.json} ESSENCE_DRAGON;
 * the SkyBlock XP per perk tier is research 02 sec "Essence Shop" (2/2/3/5/7 for a 5-tier perk, 2/2/3/5/7/8/8/8/9/10
 * for a 10-tier one; the Dragon shop totals <b>185</b>, asserted by a test). The altar's chat lines are SkyHanni's
 * {@code misc.draconicsacrifice.*} ("SACRIFICE! You turned X into N Dragon Essence!", "BONUS LOOT! ... from your
 * sacrifice!"). Neither the research nor the data gives the essence each item is worth or the perks' effect sizes:
 * those are designed here, marked TUNE, and calibrated against the bazaar snapshot (Dragon Essence ~3.4k coins,
 * a common dragon fragment ~13k, a Superior fragment ~61k, Ritual Residue ~2M).
 */
public final class DraconicMath {

    private DraconicMath() {}

    // ============================================================================================ the altar

    public static final String[] VARIANTS = {"YOUNG", "OLD", "PROTECTOR", "STRONG", "UNSTABLE", "WISE", "SUPERIOR", "HOLY"};

    /** Essence per armour piece of a common dragon set (TUNE: ~ the piece's fragment cost x 10 essence). */
    public static final Map<String, Integer> PIECE = Map.of("HELMET", 50, "CHESTPLATE", 80, "LEGGINGS", 70, "BOOTS", 40);

    /** Everything the altar accepts and what it is worth in essence (TUNE, see the class note). */
    public static final Map<String, Integer> SACRIFICE = build();

    private static Map<String, Integer> build() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String v : VARIANTS) {
            m.put(v + "_FRAGMENT", v.equals("SUPERIOR") ? 15 : 3);                     // TUNE: ~13k vs ~61k coins
            for (Map.Entry<String, Integer> p : PIECE.entrySet())
                m.put(v + "_DRAGON_" + p.getKey(), p.getValue() * (v.equals("SUPERIOR") ? 3 : 1));   // TUNE
        }
        m.put("ASPECT_OF_THE_DRAGON", 100);            // TUNE
        m.put("DRAGON_CLAW", 200);                     // TUNE: a ~2M stone
        m.put("DRAGON_SCALE", 25);                     // TUNE
        m.put("DRAGON_HORN", 1000);                    // TUNE: a ~15M stone; the altar is a sink, not a market
        m.put("ENDER_DRAGON;3", 300);                  // TUNE: Epic Ender Dragon pet
        m.put("ENDER_DRAGON;4", 1500);                 // TUNE: Legendary Ender Dragon pet
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Essence for {@code amount} of {@code itemId} at the given scale dial; 0 = the altar refuses it. */
    public static long essenceFor(String itemId, int amount, double scale) {
        Integer per = itemId == null ? null : SACRIFICE.get(itemId.toUpperCase(Locale.ROOT));
        if (per == null || amount <= 0) return 0;
        return Math.max(1, Math.round(per * (long) amount * Math.max(0, scale)));
    }

    /**
     * The chance that one sacrifice also pays bonus loot: linear in the essence it paid (so splitting a stack never
     * helps below the cap), capped, then multiplied by the Endermite pet's "Sacrificer" (+{@code sacrificerPct}%).
     */
    public static double bonusChance(long essence, double perEssence, double cap, double sacrificerPct) {
        double c = Math.min(Math.max(0, cap), Math.max(0, essence) * Math.max(0, perEssence));
        return Math.min(1.0, c * (1 + Math.max(0, sacrificerPct) / 100.0));
    }

    // ============================================================================================ the shop

    /**
     * One Dragon Essence perk. {@code id} = NEU's key, {@code costs[i]} = essence for level i+1, {@code perLevel} =
     * the effect per level (TUNE), {@code effect} = lore with {@code %s} for the value at the shown level.
     */
    public record Perk(String id, String name, String icon, int[] costs, double perLevel, String effect) {
        public int max() { return costs.length; }
        public double value(int level) { return perLevel * Math.max(0, Math.min(level, max())); }
    }

    public static final List<Perk> PERKS = List.of(
            new Perk("flat_damage_vs_ender", "Heavy Hand", "IRON_SWORD", new int[]{100, 200, 300, 400, 500}, 2,
                    "&7Deal &c+%s &7flat damage to &5Ender &7monsters."),                                   // TUNE
            new Perk("mana_after_ender_kill", "Recharge", "LAPIS_LAZULI", new int[]{100, 100, 100, 100, 100, 100, 100, 100, 100, 100}, 1,
                    "&7Killing an &5Ender &7monster restores &b%s% &7of your mana."),                        // TUNE
            new Perk("fero_vs_dragons", "Nestfury", "BLAZE_POWDER", new int[]{125, 250, 500, 1000, 1500}, 2,
                    "&7Gain &c+%s ⫽ Ferocity &7against &5Ender Dragons&7."),                               // TUNE
            new Perk("inc_zealots_odds", "Zealot's Fortune", "ENDER_EYE", new int[]{150, 500, 1250, 2000, 3000}, 5,
                    "&7Zealots near you are &a%s% &7more likely to\n&7be Special Zealots."),                  // TUNE
            new Perk("combat_wisdom_in_end", "Ender Training", "EXPERIENCE_BOTTLE", new int[]{250, 1250, 5000}, 2,
                    "&3+%s ☯ Combat Wisdom &7while in &5The End&7."),                                         // TUNE
            new Perk("edrag_cd", "Dragon Infusion", "DRAGON_BREATH", new int[]{500, 1500, 2500, 3500, 4500}, 4,
                    "&7Your &6Wyrmbreath Blade&7's ability costs\n&b%s% &7less mana."),                       // TUNE
            new Perk("dragon_reforges_buff", "Twin Fang", "PRISMARINE_SHARD", new int[]{1500, 2250, 3250, 4500, 6500}, 2,
                    "&7The reforge stats of &9Fabled&7, &9Renowned\n&7and &9Spiked &7gear are &a%s% &7stronger."),  // TUNE
            new Perk("increased_sup_chances", "Wyrm Caller", "GOLDEN_HORSE_ARMOR", new int[]{5000}, 25,
                    "&7Every &5Summoning Eye &7you place makes a\n&6Superior Dragon &a%s% &7more likely &8(your\n&8share of the eight)&7."),  // TUNE
            new Perk("unbridled_rage", "Unbridled Rage", "REDSTONE", new int[]{1000, 1500, 2000, 3000, 5000}, 2,
                    "&7Deal &c+%s% &7damage to &5Ender Dragons\n&7once they fall below half health."));         // TUNE

    /** The perk with NEU id {@code id}, or null. Effect lore is one string with '\n' between lines. */
    public static Perk perk(String id) {
        for (Perk p : PERKS) if (p.id().equals(id)) return p;
        return null;
    }

    /** SkyBlock XP for buying tier {@code tier} (1-based) of a perk with {@code max} tiers (research 02). */
    public static int sbXp(int tier, int max) {
        int[] five = {2, 2, 3, 5, 7}, ten = {2, 2, 3, 5, 7, 8, 8, 8, 9, 10};
        int[] t = max > 5 ? ten : five;
        if (tier < 1 || tier > t.length) return 0;
        return t[tier - 1];
    }

    /** SkyBlock XP for every tier of every perk (research: Dragon shop 185). */
    public static int totalSbXp() {
        int s = 0;
        for (Perk p : PERKS) for (int t = 1; t <= p.max(); t++) s += sbXp(t, p.max());
        return s;
    }

    /** Essence to finish a perk from level {@code from}. */
    public static long costToMax(Perk p, int from) {
        long s = 0;
        for (int i = Math.max(0, from); i < p.max(); i++) s += p.costs()[i];
        return s;
    }

    /** Lore lines of a perk at a level (value filled in). */
    public static List<String> effectLines(Perk p, int level) {
        String v = fmt(p.value(Math.max(1, level)));
        List<String> out = new ArrayList<>();
        for (String s : p.effect().split("\n")) out.add(s.replace("%s", v));
        return out;
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(Math.round(d * 100) / 100.0);
    }

    // ============================================================================================ the dragons

    /**
     * Superior weight after Wyrm Caller: {@code base x (1 + bonusPct/100 x share)}, where share = eyes placed by
     * players holding the perk / 8 (the dragon belongs to whoever filled the altar, so each eye carries its owner's
     * share of the bonus).
     */
    public static double superiorWeight(double base, double bonusPctPerFullAltar, int perkEyes) {
        double share = Math.max(0, Math.min(8, perkEyes)) / 8.0;
        return Math.max(0, base) * (1 + Math.max(0, bonusPctPerFullAltar) / 100.0 * share);
    }

    /** Dragon Essence a participant earns from a dragon kill: loot quality / divisor (TUNE), at least 1 if they took part. */
    public static long dragonEssence(int quality, double divisor) {
        if (quality <= 0 || divisor <= 0) return 0;
        return Math.max(1, Math.round(quality / divisor));
    }
}
