package com.skyclone.mod.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure numbers of the storage module: no Bukkit, no Sky. Unit-tested in
 * {@code plugin/src/test/java/com/skyclone/mod/storage/StorageMathTest.java} and re-checked live by the
 * {@code storage.formulas} self-test.
 */
final class StorageMath {

    private StorageMath() {}

    /** research 06 / item lore: Small 9, Medium 18, Large 27, Greater 36, Jumbo 45. */
    static final int[] BACKPACK_SIZES = {9, 18, 27, 36, 45};

    /** Ender chest page sizes: 8 pages of 45 and the 9th (upgrade VIII) of 36 (research 05 account upgrades). */
    static int enderPageSize(int pageIndex, int pages, int lastPageSize) {
        if (pageIndex < 0 || pageIndex >= pages) return 0;
        return pageIndex == pages - 1 ? clamp(lastPageSize, 9, 45) : 45;
    }

    /** Total ender chest slots for {@code unlocked} pages. */
    static int enderSlots(int unlocked, int pages, int lastPageSize) {
        int t = 0;
        for (int i = 0; i < Math.min(unlocked, pages); i++) t += enderPageSize(i, pages, lastPageSize);
        return t;
    }

    /**
     * Capacity of one sack: its base capacity grows by +200% of the ORIGINAL per lining, up to
     * {@code maxLinings} (lore of Pocket Sack-in-a-Sack: "+200% its original capacity", "up to three times").
     */
    static long sackCapacity(long base, int linings, int maxLinings, double perLining) {
        int n = clamp(linings, 0, Math.max(0, maxLinings));
        return Math.max(0, Math.round(base * (1.0 + perLining * n)));
    }

    /**
     * A capacity raised by {@code pct} percent (Hunting's Sack Size: +1% per level), rounded down so a sack never
     * claims room it does not have. Negative or non-finite percentages count as 0; never overflows.
     */
    static long withPercent(long capacity, double pct) {
        if (capacity <= 0) return Math.max(0, capacity);
        if (!(pct > 0) || Double.isInfinite(pct)) return capacity;
        double v = Math.floor(capacity * (1.0 + pct / 100.0) + 1e-6);   // 1e-6: 2,240 x 1.05 is 2,352, not 2,351.999...
        return v >= Long.MAX_VALUE / 2.0 ? Long.MAX_VALUE / 2 : (long) v;
    }

    /** How much of {@code amount} fits when {@code stored} already sits against {@code capacity}, each unit weighing {@code weight}. */
    static long fits(long capacity, long storedWeight, long amount, long weight) {
        if (amount <= 0 || weight <= 0) return 0;
        long room = capacity - storedWeight;
        if (room <= 0) return 0;
        return Math.min(amount, room / weight);
    }

    /**
     * Parse "0,0,10,25" into thresholds. Blank or bad parts become {@code Integer.MAX_VALUE} (locked),
     * so a typo in a dial locks a slot rather than opening every slot.
     */
    static int[] thresholds(String csv) {
        if (csv == null || csv.isBlank()) return new int[0];
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i].trim();
            try { out[i] = s.isEmpty() ? Integer.MAX_VALUE : (int) Math.round(Double.parseDouble(s)); }
            catch (NumberFormatException e) { out[i] = Integer.MAX_VALUE; }
        }
        return out;
    }

    /** How many entries of the threshold list {@code have} reaches (slot i is open when have >= t[i]). */
    static int unlockedCount(int[] thresholds, long have) {
        int n = 0;
        for (int t : thresholds) if (have >= t) n++;
        return n;
    }

    /** Is the slot at {@code index} open? */
    static boolean unlocked(int[] thresholds, int index, long have) {
        return index >= 0 && index < thresholds.length && have >= thresholds[index];
    }

    /**
     * Wave 5: slots/pages open when either path unlocks them. The reference sells these as Community Shop account
     * upgrades (research 05 6.4: Armor/Equipment Wardrobe Slots, Ender Chest Pages, Sack of Sacks); here the
     * SkyBlock-level list stands in for gems and the Community Shop tier (bought with coins/bits, earned in game)
     * adds on top: {@code min(max, levelCount + shopTier)}.
     */
    static int openCount(int[] levelThresholds, long level, int shopTier, int max) {
        return clamp(unlockedCount(levelThresholds, level) + Math.max(0, shopTier), 0, Math.max(0, max));
    }

    /**
     * The SkyBlock level that opens slot {@code index} given the Community Shop tier already owned (the shop tier
     * shifts every level gate down by one slot per tier). {@code Integer.MAX_VALUE} when no level ever opens it;
     * 0 when it is open by the shop alone.
     */
    static int levelFor(int[] levelThresholds, int index, int shopTier) {
        int i = index - Math.max(0, shopTier);
        if (i < 0) return 0;
        if (i >= levelThresholds.length) return Integer.MAX_VALUE;
        // thresholds may be out of order in a hand-edited dial: the i-th smallest is what opens the (i+1)-th slot
        int[] sorted = levelThresholds.clone();
        java.util.Arrays.sort(sorted);
        return sorted[i];
    }

    /**
     * Rune Sack grouping (wave 5): ids of one rune type ordered by tier. {@code runes} maps id -> "KEY:tier"; the
     * result maps KEY -> [tier I id, tier II id, tier III id] (null where a tier is not registered), keys in the
     * order the ids came. Ids that do not parse are left out (the caller lists them one per icon).
     */
    static java.util.LinkedHashMap<String, String[]> runeGroups(java.util.Map<String, String> runes, int maxTier) {
        java.util.LinkedHashMap<String, String[]> out = new java.util.LinkedHashMap<>();
        if (runes == null) return out;
        for (java.util.Map.Entry<String, String> en : runes.entrySet()) {
            String v = en.getValue();
            if (v == null) continue;
            int c = v.lastIndexOf(':');
            if (c <= 0) continue;
            int tier;
            try { tier = Integer.parseInt(v.substring(c + 1).trim()); } catch (NumberFormatException e) { continue; }
            if (tier < 1 || tier > maxTier) continue;
            out.computeIfAbsent(v.substring(0, c), k -> new String[maxTier])[tier - 1] = en.getKey();
        }
        return out;
    }

    /**
     * Sack of Sacks slots (research 02 Tropical Fish collection + research 05 account upgrade):
     * {@code accountSlots} from the level-gated upgrade tiers, plus the collection: {@code unlockSlots} at
     * tier IV, +4 at V, VI, VII, VIII and +5 at IX.
     */
    static int sackBagSlots(int accountSlots, int tropicalTier, int unlockSlots) {
        int n = Math.max(0, accountSlots);
        if (tropicalTier >= 4) n += Math.max(0, unlockSlots);
        if (tropicalTier >= 5) n += 4;
        if (tropicalTier >= 6) n += 4;
        if (tropicalTier >= 7) n += 4;
        if (tropicalTier >= 8) n += 4;
        if (tropicalTier >= 9) n += 5;
        return Math.min(45, n);
    }

    /** Quiver slots (research 02 String collection): {@code base} at III, +9 at VI, +9 at IX. 0 below III. */
    static int quiverSlots(int stringTier, int base) {
        if (stringTier < 3) return 0;
        int n = base;
        if (stringTier >= 6) n += 9;
        if (stringTier >= 9) n += 9;
        return clamp(n, 0, 45);
    }

    /** "50000000:9,100000000:18" -> slots for the largest threshold the value reaches. */
    static int tieredValue(String spec, double value) {
        int best = 0;
        if (spec == null) return 0;
        for (String part : spec.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2) continue;
            try {
                double need = Double.parseDouble(kv[0].trim());
                int v = Integer.parseInt(kv[1].trim());
                if (value >= need && v > best) best = v;
            } catch (NumberFormatException ignored) { }
        }
        return best;
    }

    /** Rows needed to show {@code slots} free slots plus one navigation row. */
    static int rowsFor(int slots) {
        return clamp((Math.max(1, slots) + 8) / 9 + 1, 2, 6);
    }

    /** Index of the smallest backpack size that is at least {@code slots}; -1 when none. */
    static int sizeIndex(int slots) {
        for (int i = 0; i < BACKPACK_SIZES.length; i++) if (BACKPACK_SIZES[i] == slots) return i;
        return -1;
    }

    /** Split {@code total} into stacks of {@code max}. */
    static List<Integer> stacks(long total, int max) {
        List<Integer> out = new ArrayList<>();
        if (max <= 0) return out;
        long left = total;
        while (left > 0) {
            int n = (int) Math.min(max, left);
            out.add(n);
            left -= n;
        }
        return out;
    }

    /** Lower-case letters and digits only: "Enchanted Diamond", "enchanted_diamond" and "ENCHANTED-DIAMOND" all agree. */
    static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toLowerCase(java.util.Locale.ROOT).toCharArray()) if (Character.isLetterOrDigit(c)) b.append(c);
        return b.toString();
    }

    /**
     * Resolve what a player typed after {@code /gfs} to item ids. {@code names} maps candidate id -> plain display
     * name, in preference order. An exact id, an exact name or an exact squashed id wins alone; otherwise every
     * candidate whose name or id CONTAINS the query is returned (the caller lists them as "did you mean").
     */
    static List<String> matchItem(String query, java.util.Map<String, String> names) {
        List<String> out = new ArrayList<>();
        String q = norm(query);
        if (q.isEmpty() || names == null) return out;
        String asId = query.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
        if (names.containsKey(asId)) { out.add(asId); return out; }
        for (java.util.Map.Entry<String, String> en : names.entrySet()) {
            if (norm(en.getValue()).equals(q) || norm(en.getKey()).equals(q)) { out.add(en.getKey()); return out; }
        }
        for (java.util.Map.Entry<String, String> en : names.entrySet()) {
            if (norm(en.getValue()).contains(q) || norm(en.getKey()).contains(q)) out.add(en.getKey());
        }
        return out;
    }

    /** "/gfs diamond 64" -> amount 64; "/gfs diamond" -> {@code def}; "/gfs diamond all" -> Long.MAX_VALUE. Never below 1. */
    static long gfsAmount(String last, long def) {
        if (last == null) return def;
        String t = last.trim().toLowerCase(java.util.Locale.ROOT).replace(",", "");
        if (t.equals("all") || t.equals("max")) return Long.MAX_VALUE;
        long mult = 1;
        if (t.endsWith("k")) { mult = 1000; t = t.substring(0, t.length() - 1); }
        try { return Math.max(1, Long.parseLong(t) * mult); } catch (NumberFormatException e) { return -1; }
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    static int clampInt(long v) { return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v)); }
}
