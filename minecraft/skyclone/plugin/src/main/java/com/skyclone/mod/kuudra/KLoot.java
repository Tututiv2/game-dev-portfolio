package com.skyclone.mod.kuudra;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Rolls the two reward chests, the June-2025 model (audit 2026-10-06 K1-K23; SkyblockMoney/work/kuudra.md sec 1-2):
 * the FREE chest is Crimson Essence; the PAID chest (opened with the tier's Leviathan Key) is
 * <ol>
 *   <li>the GUARANTEED part: Kraken shards 1/1/2/2/3, Leviathan Teeth 1/1/2/2/3 and Crimson Essence;</li>
 *   <li>one slot-1 roll over the tier's weighted rows (Basic-tier armour, staves, molten equipment, shards, cores...);</li>
 *   <li>one slot-2 roll (mana books at level == tier, the Leviathan Tentacle, the Wheel, the ultimate books, teeth /
 *       essence / pearl bundles, shards...);</li>
 *   <li>the independent rares (pet, Enrager, Heat Core, Tentacle Dye).</li>
 * </ol>
 * Pure: ids are checked through {@code exists}, so a chest never holds an item nobody registered (a row whose item is
 * missing drops out of its slot and the rest share its weight).
 */
public final class KLoot {

    public static final String ESSENCE = "ESSENCE_CRIMSON";
    public static final String TEETH = "KUUDRA_TEETH";
    /** Guaranteed in every paid chest since June 2025; never a random roll, so never Wheel material. */
    public static final String KRAKEN = "SHARD_KRAKEN";
    public static final String ANANKE_FEATHER = "ANANKE_FEATHER";

    private KLoot() {}

    /** What one player's two chests hold. */
    public record Rolled(Map<String, Integer> free, Map<String, Integer> paid) {}

    /**
     * Who is opening, as numbers.
     *
     * @param essenceBonusPct  extra % on every Crimson Essence in both chests (event dial, Leviathan pet, hunting attribute)
     * @param extraTeethPct    hunting attribute "Leviathan's Box" (reference Kuudra's Box, +5%/level): chance of one
     *                         extra tooth; every full 100% is a sure one
     * @param paidEssenceScale dial {@code kuudra.paid-essence-scale} on the guaranteed paid essence (an estimate)
     * @param anankePct        hunting attribute "Wings of Destiny" (Ananke shard): +% weight on the Ananke Feather row
     */
    public record Mods(double essenceBonusPct, double extraTeethPct, double paidEssenceScale, double anankePct) {
        public static final Mods NONE = new Mods(0, 0, 1, 0);
    }

    public static Rolled roll(KCatalog.Loot t, Random rng, Predicate<String> exists, double essenceBonusPct) {
        return roll(t, rng, exists, new Mods(essenceBonusPct, 0, 1, 0));
    }

    public static Rolled roll(KCatalog.Loot t, Random rng, Predicate<String> exists, double essenceBonusPct, double extraTeethPct) {
        return roll(t, rng, exists, new Mods(essenceBonusPct, extraTeethPct, 1, 0));
    }

    public static Rolled roll(KCatalog.Loot t, Random rng, Predicate<String> exists, Mods m) {
        Map<String, Integer> free = new LinkedHashMap<>();
        Map<String, Integer> paid = new LinkedHashMap<>();
        double ess = 1 + Math.max(0, m.essenceBonusPct()) / 100.0;
        int fe = (int) Math.round(range(t.freeEssence, rng) * ess);
        if (fe > 0 && exists.test(ESSENCE)) free.merge(ESSENCE, fe, Integer::sum);

        // 1. the guaranteed part
        for (KCatalog.Entry g : t.guaranteed) add(paid, g, exists, ess);
        int pe = (int) Math.round(range(t.paidEssence, rng) * Math.max(0, m.paidEssenceScale()) * ess);
        if (pe > 0 && exists.test(ESSENCE)) paid.merge(ESSENCE, pe, Integer::sum);
        // 2-3. one roll per slot
        add(paid, pick(t.slot1, rng, exists, m), exists, ess);
        add(paid, pick(t.slot2, rng, exists, m), exists, ess);
        int extra = extraTeeth(m.extraTeethPct(), rng.nextDouble());
        if (extra > 0 && exists.test(TEETH)) paid.merge(TEETH, extra, Integer::sum);
        // 4. rares
        for (KCatalog.Rare r : t.rares) {
            if (r.chance <= 0 || r.item == null || !exists.test(r.item)) continue;
            if (rng.nextDouble() < r.chance) paid.merge(r.item, 1, Integer::sum);
        }
        if (paid.isEmpty() && exists.test(ESSENCE)) paid.put(ESSENCE, Math.max(1, range(t.paidEssence, rng)));
        return new Rolled(free, paid);
    }

    private static void add(Map<String, Integer> paid, KCatalog.Entry e, Predicate<String> exists, double essMult) {
        if (e == null || e.item == null || e.count <= 0 || !exists.test(e.item)) return;
        int n = ESSENCE.equals(e.item) ? (int) Math.max(1, Math.round(e.count * essMult)) : e.count;
        paid.merge(e.item, n, Integer::sum);
    }

    /** The weight a row rolls with (the Ananke Feather row is lifted by Wings of Destiny). */
    static double weightOf(KCatalog.Entry e, Mods m) {
        double w = Math.max(0, e.weight);
        if (ANANKE_FEATHER.equals(e.item) && m != null && m.anankePct() > 0) w *= 1 + m.anankePct() / 100.0;
        return w;
    }

    /** One row of a slot, by weight, among rows whose item exists; null when none does. */
    static KCatalog.Entry pick(List<KCatalog.Entry> slot, Random rng, Predicate<String> exists, Mods m) {
        double total = 0;
        for (KCatalog.Entry e : slot) if (e.item != null && exists.test(e.item)) total += weightOf(e, m);
        if (total <= 0) return null;
        double r = rng.nextDouble() * total;
        KCatalog.Entry last = null;
        for (KCatalog.Entry e : slot) {
            if (e.item == null || !exists.test(e.item)) continue;
            double w = weightOf(e, m);
            if (w <= 0) continue;
            last = e;
            r -= w;
            if (r < 0) return e;
        }
        return last;
    }

    /** Chance (0..1) that one roll of {@code slot} lands on {@code item} (any row), among rows that exist. */
    public static double chance(List<KCatalog.Entry> slot, String item, Predicate<String> exists) {
        double total = KCatalog.Loot.weight(slot, exists), hit = 0;
        if (total <= 0) return 0;
        for (KCatalog.Entry e : slot) if (item.equals(e.item) && e.weight > 0 && exists.test(e.item)) hit += e.weight;
        return hit / total;
    }

    /** "17.16%" or, under 0.1%, "1 in 4,662" (the loot preview). */
    public static String odds(double p) {
        if (!(p > 0)) return "-";
        if (p >= 0.001) return String.format(java.util.Locale.ROOT, "%.2f%%", p * 100);
        return String.format(java.util.Locale.ROOT, "1 in %,d", Math.round(1 / p));
    }

    /** Extra teeth from a percentage: one per full 100%, plus one more when {@code unit} falls under the rest. */
    public static int extraTeeth(double pct, double unit) {
        if (!(pct > 0)) return 0;
        int sure = (int) Math.floor(pct / 100.0);
        return sure + (unit < (pct - sure * 100.0) / 100.0 ? 1 : 0);
    }

    public static boolean isShard(String id) { return id != null && id.startsWith("SHARD_"); }

    /** Registered shards of one rarity on this tier's Wheel ladder. */
    static List<String> ofRarity(KCatalog.Loot t, String rarity, Predicate<String> exists) {
        List<String> out = new java.util.ArrayList<>();
        for (KCatalog.Shard s : t.shards) if (rarity.equals(s.rarity) && !KRAKEN.equals(s.id) && exists.test(s.id)) out.add(s.id);
        return out;
    }

    /**
     * The Wheel of Embers (reference Wheel of Fate): the chest's shard becomes a random registered shard of the NEXT
     * rarity up that the ladder has (skipping a rarity with none). Null when it is already at the top.
     */
    public static String upgradeShard(KCatalog.Loot t, String current, Random rng, Predicate<String> exists) {
        String r = t.shardRarityOf(current);
        int at = r == null ? -1 : KCatalog.SHARD_RARITIES.indexOf(r);
        if (at < 0) return null;
        for (int i = at + 1; i < KCatalog.SHARD_RARITIES.size(); i++) {
            List<String> next = ofRarity(t, KCatalog.SHARD_RARITIES.get(i), exists);
            if (!next.isEmpty()) return pick(next, rng);
        }
        return null;
    }

    /** The rolled shard in a chest's contents (the guaranteed Kraken shards are not one), or null. */
    public static String shardIn(Map<String, Integer> paid) {
        for (String id : paid.keySet()) if (isShard(id) && !KRAKEN.equals(id)) return id;
        return null;
    }

    static String pick(List<String> list, Random rng) { return list == null || list.isEmpty() ? null : list.get(rng.nextInt(list.size())); }

    static int range(int[] r, Random rng) {
        if (r == null || r.length == 0) return 0;
        if (r.length == 1 || r[1] <= r[0]) return r[0];
        return r[0] + rng.nextInt(r[1] - r[0] + 1);
    }
}
