package com.skyclone.mod.crimson;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The isle's rules as pure functions (no Bukkit), so unit tests and self-tests check the same code the
 * game runs: faction ranks, the daily quest roll, Dojo grading and belts, armor prestige maths.
 */
public final class CrimsonRules {

    private CrimsonRules() {}

    public static final String MAGE = "mage", BARBARIAN = "barbarian";
    public static final List<String> FACTIONS = List.of(MAGE, BARBARIAN);

    /** "mage", "Mages", "MAGE" -> "mage"; "barbarians" -> "barbarian"; null otherwise. */
    public static String faction(String s) {
        if (s == null) return null;
        String k = s.trim().toLowerCase(Locale.ROOT);
        if (k.endsWith("s")) k = k.substring(0, k.length() - 1);
        return FACTIONS.contains(k) ? k : null;
    }

    public static String rival(String faction) { return MAGE.equals(faction) ? BARBARIAN : MAGE; }

    // ---- ranks ----------------------------------------------------------------------------------

    /** Index of the highest rank whose threshold {@code rep} reaches (0 = the first rank). */
    public static int rankIndex(List<CrimsonTables.Rank> ranks, int rep) {
        int best = 0;
        for (int i = 0; i < ranks.size(); i++) if (rep >= ranks.get(i).rep) best = i;
        return best;
    }

    /** Reputation still needed for the next rank, or -1 at the top. */
    public static int toNext(List<CrimsonTables.Rank> ranks, int rep) {
        int i = rankIndex(ranks, rep);
        return i + 1 >= ranks.size() ? -1 : ranks.get(i + 1).rep - rep;
    }

    // ---- daily quests ------------------------------------------------------------------------------

    /**
     * Pick {@code count} distinct quests for one player and one day: only those offered to the faction and
     * rank, at most two of any one type, deterministic for a seed (uuid + day), so a reconnect or a
     * restart never rerolls the board.
     */
    public static List<CrimsonTables.Quest> rollQuests(List<CrimsonTables.Quest> all, String faction, int rank, long seed, int count) {
        List<CrimsonTables.Quest> pool = new ArrayList<>();
        for (CrimsonTables.Quest q : all) if (q.forFaction(faction) && rank >= q.minRank) pool.add(q);
        Random r = new Random(seed);
        // shuffle deterministically (Fisher-Yates with our own Random so it never depends on the JDK's)
        for (int i = pool.size() - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            CrimsonTables.Quest t = pool.get(i); pool.set(i, pool.get(j)); pool.set(j, t);
        }
        List<CrimsonTables.Quest> out = new ArrayList<>();
        for (CrimsonTables.Quest q : pool) {
            if (out.size() >= count) break;
            long sameType = out.stream().filter(o -> group(o.type).equals(group(q.type))).count();
            if (sameType >= 2) continue;
            out.add(q);
        }
        for (CrimsonTables.Quest q : pool) {             // fill up if the type cap left gaps
            if (out.size() >= count) break;
            if (!out.contains(q)) out.add(q);
        }
        return out;
    }

    private static String group(String type) { return type.startsWith("FISH") ? "FISH" : type; }

    /** The seed of one player's board on one day. */
    public static long questSeed(String uuid, String day) {
        long h = 1125899906842597L;
        String s = uuid + "|" + day;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return h;
    }

    // ---- the Dojo ---------------------------------------------------------------------------------------

    public static String grade(List<CrimsonTables.DojoRank> ranks, int points) {
        String best = "F";
        int bestMin = -1;
        for (CrimsonTables.DojoRank r : ranks) if (points >= r.min && r.min > bestMin) { best = r.rank; bestMin = r.min; }
        return best;
    }

    /** The best belt a points total earns, or null. */
    public static CrimsonTables.Belt belt(List<CrimsonTables.Belt> belts, int total) {
        CrimsonTables.Belt best = null;
        for (CrimsonTables.Belt b : belts) if (total >= b.points && (best == null || b.points > best.points)) best = b;
        return best;
    }

    public static int clampPoints(double v, int max) { return (int) Math.max(0, Math.min(max, Math.round(v))); }

    /** Force: plain hits score 5, critical hits 12 (TUNE: about 150 hits in 30 s for an S). */
    public static int forceHit(boolean crit) { return crit ? 12 : 5; }

    /** Mastery: a target hit while green scores 100, early hits 25 (TUNE). Ten targets = 1,000. */
    public static int masteryHit(boolean green) { return green ? 100 : 25; }

    /** Swiftness: points for reaching a tile, more for faster (TUNE): 100 at once, 25 at the 2.5s limit. */
    public static int swiftnessStep(long millis) {
        double t = Math.max(0, Math.min(2500, millis)) / 2500.0;
        return (int) Math.round(100 - 75 * t);
    }

    /** Control: fraction of time looked at, scaled to the maximum. */
    public static int control(int onTicks, int totalTicks, int max) {
        return totalTicks <= 0 ? 0 : clampPoints((double) onTicks / totalTicks * max * 1.25, max);
    }

    // ---- mini-bosses -------------------------------------------------------------------------------------

    /** A damage dial as a multiplier: negative, NaN or infinite reads as "as designed" (1.0), never as healing. */
    public static double damageScale(double dial) {
        return Double.isFinite(dial) && dial >= 0 ? dial : 1.0;
    }

    // ---- mini-boss spoils (wave 5) ------------------------------------------------------------------------

    /**
     * Does a player who dealt {@code dealt} of a boss's {@code maxHp} share the kill? The killer always does;
     * everyone else needs {@code minPercent}% of the boss's health (a world boss is fought by a crowd, and in the
     * reference every real contributor is paid, not only the last hit). Bad numbers never let a 0-damage
     * bystander in.
     */
    public static boolean sharesKill(double dealt, double maxHp, double minPercent, boolean killer) {
        if (killer) return true;
        if (!Double.isFinite(dealt) || dealt <= 0) return false;
        double pct = Double.isFinite(minPercent) ? Math.max(0, minPercent) : 1.0;
        if (!Double.isFinite(maxHp) || maxHp <= 0) return true;
        return dealt >= maxHp * pct / 100.0;
    }

    /** Players by damage dealt, most first (ties: insertion order). */
    public static <K> List<Map.Entry<K, Double>> ranking(Map<K, Double> dealt) {
        List<Map.Entry<K, Double>> out = new ArrayList<>(dealt.entrySet());
        out.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return out;
    }

    /**
     * True when {@code bossId} is the champion of the RIVAL of {@code faction}: the boss that faction's quests
     * send it after ({@code factions.json} {@code rival_boss}: mages hunt the Duke, barbarians the Pyromancer).
     * Before wave 5 the bonus reputation went the wrong way round (a barbarian killing his own Duke was paid as
     * a rival kill).
     */
    public static boolean rivalChampion(Map<String, CrimsonTables.FactionInfo> factions, String faction, String bossId) {
        if (faction == null || bossId == null || factions == null) return false;
        CrimsonTables.FactionInfo i = factions.get(faction);
        return i != null && bossId.equals(i.rival_boss);
    }

    /** The faction spoils one pledged player earns from one mini-boss, and why. */
    public record Spoils(List<CrimsonTables.LootLine> lines, boolean champion, boolean quest) {}

    /**
     * Faction-specific mini-boss loot: nothing for the unpledged; the faction's own table for any mini-boss;
     * plus the boss's {@code champion} table when it is the rival faction's champion; plus the {@code quest}
     * table when a BOSS quest for it was open on the player's board when it fell.
     */
    public static Spoils spoils(CrimsonTables.Factions f, String faction, String bossId, boolean questOpen) {
        List<CrimsonTables.LootLine> out = new ArrayList<>();
        if (f == null || faction == null || bossId == null) return new Spoils(out, false, false);
        CrimsonTables.BossLoot bl = f.boss_loot == null ? new CrimsonTables.BossLoot() : f.boss_loot;
        List<CrimsonTables.LootLine> own = bl.faction.get(faction);
        if (own != null) out.addAll(own);
        boolean champ = rivalChampion(f.factions, faction, bossId);
        if (champ) {
            List<CrimsonTables.LootLine> c = bl.champion.get(bossId);
            if (c != null) out.addAll(c);
        }
        if (questOpen && bl.quest != null) out.addAll(bl.quest);
        return new Spoils(out, champ, questOpen);
    }

    /**
     * A "+x% more" bonus on an integer amount: the whole part always, the fraction as a chance ({@code roll}
     * in [0,1)). 10 essence at +25% = 2 plus a 50% chance of a third. Never negative.
     */
    public static int percentBonus(int amount, double pct, double roll) {
        if (amount <= 0 || !Double.isFinite(pct) || pct <= 0) return 0;
        double v = amount * pct / 100.0;
        int whole = (int) Math.floor(v);
        return whole + (roll < v - whole ? 1 : 0);
    }

    /** Vanquisher odds with "Crimson Serendipity" (+x% relative, the reference attribute's wording). */
    public static double vanquisherChance(double base, double serendipityPct) {
        double b = Double.isFinite(base) ? Math.max(0, base) : 0;
        double s = Double.isFinite(serendipityPct) ? Math.max(0, serendipityPct) : 0;
        return Math.min(1.0, b * (1 + s / 100.0));
    }

    // ---- synthesizers (wave 5) ---------------------------------------------------------------------------

    /**
     * What a Synthesizer accepts, from the reference lore (NEU): v1 X -> 30 Health; v2 adds Y -> 30 Defense;
     * v3 raises Health and Defense to 40 and adds Z -> 60 Intelligence. One fed item = one stat point (TUNE: the
     * lore shows the caps only). Empty for anything else.
     */
    public static List<CrimsonTables.SynthSlot> synthSlots(String itemId) {
        List<CrimsonTables.SynthSlot> out = new ArrayList<>();
        if (itemId == null) return out;
        switch (itemId) {
            case "SYNTHESIZER_V1" -> out.add(slot("X", "HEALTH", 30));
            case "SYNTHESIZER_V2" -> { out.add(slot("X", "HEALTH", 30)); out.add(slot("Y", "DEFENSE", 30)); }
            case "SYNTHESIZER_V3" -> { out.add(slot("X", "HEALTH", 40)); out.add(slot("Y", "DEFENSE", 40)); out.add(slot("Z", "INTELLIGENCE", 60)); }
            default -> { }
        }
        return out;
    }

    private static CrimsonTables.SynthSlot slot(String item, String stat, int cap) {
        CrimsonTables.SynthSlot s = new CrimsonTables.SynthSlot();
        s.item = item; s.stat = stat; s.cap = cap;
        return s;
    }

    /** The {@code ItemData.extra} key that stores how many of {@code item} a Synthesizer has eaten. */
    public static String synthKey(String item) { return "synth_" + item.toLowerCase(Locale.ROOT); }

    /** How many of {@code offered} items one feed may take: never past the cap, never negative. */
    public static int synthTake(int have, int cap, int offered) {
        return Math.max(0, Math.min(offered, cap - Math.max(0, have)));
    }

    // ---- armor prestige -----------------------------------------------------------------------------------

    public static final String[] TIER_PREFIX = {"", "HOT_", "BURNING_", "FIERY_", "INFERNAL_"};
    public static final String[] TIER_NAME = {"Basic", "Hot", "Burning", "Fiery", "Infernal"};

    /** "HOT_CRIMSON_CHESTPLATE" -> 1 ; "CRIMSON_HELMET" -> 0 ; unknown -> -1. */
    public static int tierOf(String id) {
        if (id == null) return -1;
        for (int t = TIER_PREFIX.length - 1; t >= 1; t--) if (id.startsWith(TIER_PREFIX[t])) return t;
        return 0;
    }

    /** The next prestige of a Kuudra-line piece id, or null at Infernal. */
    public static String nextPrestige(String id) {
        int t = tierOf(id);
        if (t < 0 || t >= TIER_PREFIX.length - 1) return null;
        String base = id.substring(TIER_PREFIX[t].length());
        return TIER_PREFIX[t + 1] + base;
    }

    // ---- Heavy Pearls (Belly of the Beast) -------------------------------------------------------------

    /** Today's order of the {@code spots} pearl spots for one player: a seeded shuffle, the same all day. */
    public static int[] pearlOrder(String uuid, String day, int spots) {
        int[] o = new int[Math.max(0, spots)];
        for (int i = 0; i < o.length; i++) o[i] = i;
        java.util.Random r = new java.util.Random(questSeed(uuid, "pearls:" + day));
        for (int i = o.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = o[i]; o[i] = o[j]; o[j] = t;
        }
        return o;
    }

    /**
     * Which pearls are floating right now: spot index -> pearl number. Pearl {@code j} of the day sits on spot
     * {@code order[j % spots]}; the day allows {@code allowance} pearls; a spot shows the lowest pending pearl
     * that belongs to it, so collecting in any order never strands one.
     */
    public static Map<Integer, Integer> pearlsShowing(int[] order, int allowance, Set<Integer> taken) {
        Map<Integer, Integer> out = new java.util.LinkedHashMap<>();
        if (order.length == 0) return out;
        for (int j = 0; j < allowance; j++) {
            if (taken.contains(j)) continue;
            out.putIfAbsent(order[j % order.length], j);
        }
        return out;
    }

    /**
     * The Leviathan clear the reference puts on a piece (Hypixel API {@code KUUDRA_COMPLETION}: tier NONE = a
     * Basic clear, HOT = a Hot clear...), as our requirement key ({@code kuudra:<tier>}, the kuudra module's
     * resolver), or null for items that carry none. {@code armorPiece} = the def has {@code extra.kuudraSet}.
     */
    public static String kuudraRequirement(String id, boolean armorPiece) {
        if (id == null) return null;
        if (armorPiece) {
            int t = tierOf(id);
            return t < 0 ? null : "kuudra:" + TIER_NAME[t].toLowerCase(java.util.Locale.ROOT);
        }
        return switch (id) {
            case "RUNIC_STAFF", "HOLLOW_WAND" -> "kuudra:basic";
            case "TORMENTOR" -> "kuudra:infernal";
            default -> null;
        };
    }

    // ---- reputation sources + Emissary discount (audit 2026-10-06: K16, K17, K18) ---------------------------

    /** Percent off an Emissary key's coin part at {@code rep}: the highest threshold reached, 0 below the first. */
    public static int emissaryDiscount(Map<String, Integer> table, int rep) {
        int best = 0, bestAt = Integer.MIN_VALUE;
        if (table == null) return 0;
        for (Map.Entry<String, Integer> e : table.entrySet()) {
            int at;
            try { at = Integer.parseInt(e.getKey().trim()); } catch (RuntimeException ex) { continue; }
            if (rep >= at && at >= bestAt && e.getValue() != null) { best = e.getValue(); bestAt = at; }
        }
        return Math.max(0, Math.min(100, best));
    }

    /** {@code coins} less {@code pct} percent, in whole coins. */
    public static double discounted(double coins, int pct) {
        if (coins <= 0) return 0;
        return Math.round(coins * (100 - Math.max(0, Math.min(100, pct))) / 100.0);
    }

    /** Reputation for a Leviathan clear of tier {@code tier} (0 = Basic .. 4 = Infernal); 0 off the table. */
    public static int kuudraRep(List<Integer> table, int tier) {
        if (table == null || tier < 0 || tier >= table.size() || table.get(tier) == null) return 0;
        return Math.max(0, table.get(tier));
    }

    /**
     * Does a Leviathan clear pay reputation now? {@code seen} = clears of this tier last time we looked (-1 = never
     * looked: the first look only takes the baseline, so clears from before this feature never pay), {@code now} =
     * clears now, {@code paidDay} = the day this tier last paid. Once a day per tier, as in the reference.
     */
    public static boolean kuudraRepDue(int seen, int now, String paidDay, String today) {
        return seen >= 0 && now > seen && today != null && !today.equals(paidDay);
    }

    /** Rollim: a claim is due when fewer than {@code days} were made and {@code hours} passed since the last one. */
    public static boolean rollimDue(int claims, long lastMillis, long nowMillis, int days, int hours) {
        return claims < days && nowMillis - lastMillis >= Math.max(0, hours) * 3_600_000L;
    }

    /** Dial text "10/20/30/40/50" (slashes, commas or spaces) -> list; null when any cell is not a number. */
    public static List<Integer> parseInts(String spec) {
        if (spec == null || spec.isBlank()) return null;
        List<Integer> out = new ArrayList<>();
        for (String t : spec.trim().split("[/,\\s]+")) {
            if (t.isEmpty()) continue;
            try { out.add(Integer.parseInt(t)); } catch (NumberFormatException e) { return null; }
        }
        return out.isEmpty() ? null : out;
    }

    /** Dial text "1000:5/3000:10" -> ordered map threshold -> value; null when malformed. */
    public static Map<String, Integer> parseThresholds(String spec) {
        if (spec == null || spec.isBlank()) return null;
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (String t : spec.trim().split("[/,\\s]+")) {
            if (t.isEmpty()) continue;
            String[] kv = t.split(":");
            if (kv.length != 2) return null;
            try { out.put(String.valueOf(Integer.parseInt(kv[0])), Integer.parseInt(kv[1])); } catch (NumberFormatException e) { return null; }
        }
        return out.isEmpty() ? null : out;
    }

    /** "1000:5/3000:10..." for a table (the dial's default text). */
    public static String thresholdsText(Map<String, Integer> table) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : table.entrySet()) b.append(b.length() == 0 ? "" : "/").append(e.getKey()).append(':').append(e.getValue());
        return b.toString();
    }

    /** "SAND:1:160" -> {"SAND:1", 160}; "ESSENCE_CRIMSON:20" -> {"ESSENCE_CRIMSON", 20}; "GOLD_INGOT" -> {.., 1}. */
    public static Object[] cell(String spec) {
        int i = spec.lastIndexOf(':');
        if (i > 0) {
            try { return new Object[]{spec.substring(0, i), Integer.parseInt(spec.substring(i + 1))}; }
            catch (NumberFormatException ignored) { }
        }
        return new Object[]{spec, 1};
    }
}
