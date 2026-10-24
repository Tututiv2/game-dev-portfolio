package com.skyclone.mod.pets;

import java.util.List;
import java.util.Map;

/**
 * Pure pet arithmetic (no Bukkit): the XP curve, level from XP, stat interpolation, the pet-XP
 * formula, Exp Share rate and Pet Score. Every number traces to research/06 §6.2-§6.6 and
 * neu-repo/constants/pets.json (loaded into {@link Curve}).
 */
public final class PetMath {

    private PetMath() {}

    public static final List<String> RARITIES = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");

    /** One pet's XP curve: the shared PET_LEVELS table at a rarity offset, plus custom levels (dragons to 200). */
    public static final class Curve {
        public final long[] levels;              // PET_LEVELS (119 entries)
        public final Map<String, Integer> offsets;

        public Curve(long[] levels, Map<String, Integer> offsets) {
            this.levels = levels;
            this.offsets = offsets;
        }

        /** XP needed to go from {@code level} to {@code level + 1} (1-based level). */
        public long step(String rarity, Map<String, Integer> customOffsets, long[] extra, int level) {
            if (level < 100) {
                int off = offset(rarity, customOffsets);
                int idx = off + level - 1;
                return idx < levels.length ? levels[idx] : levels[levels.length - 1];
            }
            int i = level - 100;
            return extra != null && i < extra.length ? extra[i] : Long.MAX_VALUE;
        }

        public int offset(String rarity, Map<String, Integer> custom) {
            // a pet with its own offset table (Bingo: all 0) uses it for every rarity, including ones it omits
            if (custom != null) return custom.getOrDefault(rarity, 0);
            Integer o = offsets.get(rarity);
            return o == null ? 0 : o;
        }

        /** Total XP to REACH {@code level} from level 1. */
        public double totalFor(String rarity, Map<String, Integer> custom, long[] extra, int level) {
            double t = 0;
            for (int l = 1; l < level; l++) t += step(rarity, custom, extra, l);
            return t;
        }

        /** Level for a total XP (1..maxLevel). */
        public int levelOf(String rarity, Map<String, Integer> custom, long[] extra, int maxLevel, double xp) {
            int l = 1;
            double left = xp;
            while (l < maxLevel) {
                long need = step(rarity, custom, extra, l);
                if (left < need) break;
                left -= need;
                l++;
            }
            return l;
        }

        /** XP into the current level (progress numerator). */
        public double into(String rarity, Map<String, Integer> custom, long[] extra, int maxLevel, double xp) {
            int l = levelOf(rarity, custom, extra, maxLevel, xp);
            return xp - totalFor(rarity, custom, extra, l);
        }
    }

    /**
     * The level whose stats a pet shows. Normal pets: the level itself (1..100). Dragons with the
     * "101:200:1" curve (neu-repo petnums stats_levelling_curve): levels 1-100 use the level-1 stats,
     * 101..200 map onto 1..100.
     */
    public static int statLevel(String curve, int level) {
        if (curve == null || curve.isEmpty()) return Math.max(1, Math.min(100, level));
        String[] s = curve.split(":");
        try {
            int min = Integer.parseInt(s[0]), max = Integer.parseInt(s[1]);
            if (level < min) return 1;
            if (level <= max) return level - min + 1;
            return max - min + 1;
        } catch (RuntimeException e) {
            return Math.max(1, Math.min(100, level));
        }
    }

    /** Linear interpolation between the level-1 and level-100 numbers (research 06 §6.1). */
    public static double lerp(double at1, double at100, int statLevel) {
        int l = Math.max(1, Math.min(100, statLevel));
        return at1 + (at100 - at1) * (l - 1) / 99.0;
    }

    /** Skills whose XP never feeds pets (research 02 line 724). */
    public static boolean feedsPets(String skill) {
        return !("CARPENTRY".equals(skill) || "TAMING".equals(skill) || "RUNECRAFTING".equals(skill) || "SOCIAL".equals(skill));
    }

    /**
     * petXP = skillXP x typeFactor x skillFactor x tamingFactor x (1 + boost%) x petMultiplier
     * (research 06 §6.3). typeFactor 1 for the pet's own skill (or an "ALL" pet), else 1/3;
     * Fishing x1.5; Alchemy/Enchanting /4 when the pet is not of that type; Taming x1.01 per level.
     */
    public static double petXp(double skillXp, String skill, String petSkill, int tamingLevel, double boostPercent, double petMult) {
        if (skillXp <= 0 || !feedsPets(skill)) return 0;
        boolean match = skill.equals(petSkill) || "ALL".equals(petSkill);
        double type = match ? 1.0 : 1.0 / 3.0;
        double sf = 1.0;
        if ("FISHING".equals(skill)) sf = 1.5;
        else if (("ALCHEMY".equals(skill) || "ENCHANTING".equals(skill)) && !match) sf = 0.25;
        double taming = 1.0 + 0.01 * Math.max(0, tamingLevel);
        return skillXp * type * sf * taming * (1 + boostPercent / 100.0) * (petMult <= 0 ? 1 : petMult);
    }

    /**
     * Exp Share rate in percent: Taming 0.2% per level (max 12%), +15% Exp Share item, +10% while the
     * Sharing-is-Caring perk is up; additive, capped at 47% (research 06 §6.3).
     */
    public static double shareRate(int tamingLevel, boolean expShareItem, boolean perk, double extra) {
        double r = Math.min(12.0, 0.2 * Math.max(0, tamingLevel));
        if (expShareItem) r += 15;
        if (perk) r += 10;
        r += extra;
        return Math.max(0, Math.min(47.0, r));
    }

    /** Pet Score points of a rarity: C1 U2 R3 E4 L5 M6. */
    public static int scoreOf(String rarity) {
        int i = RARITIES.indexOf(rarity);
        return i < 0 ? 0 : i + 1;
    }

    /** Magic Find from Pet Score via the research §6.6 table ({@code table} rows = [score, mf]). */
    public static int scoreMagicFind(int score, List<int[]> table) {
        int mf = 0;
        for (int[] row : table) if (score >= row[0]) mf = row[1];
        return mf;
    }

    /**
     * SkyBlock XP owed for a Pet Score that rose from {@code best} to {@code now}: {@code perPoint} per new point,
     * paid as ONE award (one chat line) however many points one change added. 0 when it did not rise.
     */
    public static int scoreAwardXp(int best, int now, int perPoint) {
        return now > best ? (now - Math.max(0, best)) * perPoint : 0;
    }

    /** One vanilla movement-speed attribute unit in blocks per second on flat ground (0.1 = 4.317 b/s walk). */
    public static final double BLOCKS_PER_ATTR = 43.17;

    /**
     * Movement-speed attribute of a ridden mount (Armadillo). It walks at the rider's own walking pace
     * (Speed / 100 x vanilla 0.1, Speed clamped to [100, speedCap]) at level 1, rising linearly to
     * {@code lv100Mult} times that at level 100 (dragon-style levels above 100 count as 100), and never
     * faster than {@code maxBlocksPerSec}. Before this the full multiplier applied at level 1 and the cap was
     * 0.6 (~26 b/s): a level-1 Armadillo with 350 Speed ran 21.7 b/s.
     */
    public static double mountAttr(double speedStat, int level, double lv100Mult, double speedCap, double maxBlocksPerSec) {
        double speed = Math.max(100, Math.min(Math.max(100, speedCap), speedStat));
        double t = Math.max(0, Math.min(1, (level - 1) / 99.0));
        double mult = 1 + (Math.max(1, lv100Mult) - 1) * t;
        double attr = 0.1 * mult * speed / 100.0;
        double cap = Math.max(0.05, maxBlocksPerSec / BLOCKS_PER_ATTR);
        return Math.min(cap, attr);
    }

    public static String next(String rarity) {
        int i = RARITIES.indexOf(rarity);
        return i < 0 || i + 1 >= RARITIES.size() ? null : RARITIES.get(i + 1);
    }

    /** Number formatting for ability text: at most two decimals, trailing zeros dropped. */
    public static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.format(java.util.Locale.US, "%,d", (long) Math.rint(v));
        String s = String.format(java.util.Locale.US, "%,.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ---- wave 5 -----------------------------------------------------------------------------------

    /** Eerie "Fear Harvest": {@code v} Fear for every {@code every} kills counted, kills capped at {@code cap}. */
    public static double fearHarvest(int kills, int every, int cap, double v) {
        if (every <= 0 || v <= 0) return 0;
        return Math.floor(Math.max(0, Math.min(kills, cap)) / (double) every) * v;
    }

    /**
     * Ankylosaurus "Unbreakable Will": the extra % Defense on top of the Last Stand enchant. {@code levels} = the
     * enchant's level summed over worn pieces, {@code per} = its % Defense per level (2.5), {@code boost} = the pet's a%.
     */
    public static double lastStandExtraPct(int levels, double per, double boost) {
        if (levels <= 0 || per <= 0 || boost <= 0) return 0;
        return levels * per * boost / 100.0;
    }

    /**
     * Armadillo "Burrower" energy after one pass: {@code dug} blocks cost {@code cost} each, reduced by Efficient Gait's
     * {@code saving}%; with nothing dug the tank refills {@code regen} per pass. Clamped to [0, max].
     */
    public static double tunnelEnergy(double energy, int dug, double cost, double saving, double regen, double max) {
        double e = dug > 0 ? energy - dug * cost * Math.max(0, 1 - Math.max(0, saving) / 100.0) : energy + regen;
        return Math.max(0, Math.min(max, e));
    }

    /** Kills per Beastmaster Crest growth step (the reference tooltip's "Your kills: 0/25"). */
    public static final int CREST_STEP = 25;
    /** Most growth steps a crest can take. // TUNE: the reference shows only the first threshold; 9 steps = 10x base. */
    public static final int CREST_MAX_STEPS = 9;

    /** Extra multiples of the crest's own Health / Intelligence earned by mythological kills. */
    public static int crestSteps(int kills) { return Math.max(0, Math.min(CREST_MAX_STEPS, kills / CREST_STEP)); }

    /** Fear the genre gives for worn Great Spook armour: 5 a piece, at most four pieces (weapons' WeaponMath agrees). */
    public static int armorFear(int greatSpookPieces) { return 5 * Math.max(0, Math.min(4, greatSpookPieces)); }

    /**
     * Expected extra drops from {@code bonus} more of a fortune stat when a harvest already paid {@code amount}
     * at {@code fortune}: drops = base x (1 + F/100), so the extra is amount x bonus / (100 + F).
     */
    public static double extraFromFortune(int amount, double bonus, double fortune) {
        if (amount <= 0 || bonus <= 0) return 0;
        return amount * bonus / (100.0 + Math.max(0, fortune));
    }

    /** Whole items from an expected amount: the fraction is paid by chance ({@code roll} in [0,1)). */
    public static int payout(double expected, double roll) {
        if (!(expected > 0)) return 0;
        int n = (int) Math.floor(expected);
        return n + (roll < expected - n ? 1 : 0);
    }

    /**
     * "X% more often" for something another system rolls at {@code baseChance}: the pet adds its own roll at
     * {@code baseChance x pct / 100}, so the expected count rises by exactly pct%.
     */
    public static double bonusChance(double baseChance, double pct) {
        if (!(baseChance > 0) || !(pct > 0)) return 0;
        return Math.min(1, baseChance * pct / 100.0);
    }

    /** Weighted pick: {@code roll} in [0,1); null when nothing has weight. */
    public static <T> T pick(List<T> items, java.util.function.ToDoubleFunction<T> weight, double roll) {
        double total = 0;
        for (T t : items) total += Math.max(0, weight.applyAsDouble(t));
        if (total <= 0) return null;
        double x = roll * total;
        for (T t : items) {
            x -= Math.max(0, weight.applyAsDouble(t));
            if (x < 0) return t;
        }
        return items.get(items.size() - 1);
    }

    /** The next treasure quality up ("good" -> "great" -> "outstanding"), or null at the top / unknown. */
    public static String nextQuality(String q) {
        if ("good".equals(q)) return "great";
        if ("great".equals(q)) return "outstanding";
        return null;
    }

    /** Healing after Tiger "Hemorrhage": a rise from {@code before} to {@code after} keeps only (1 - pct%) of the gain. */
    public static double bleed(double before, double after, double pct) {
        if (after <= before) return after;
        return before + (after - before) * Math.max(0, 1 - pct / 100.0);
    }
}
