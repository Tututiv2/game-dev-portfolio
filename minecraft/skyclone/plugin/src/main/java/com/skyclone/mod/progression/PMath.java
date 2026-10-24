package com.skyclone.mod.progression;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Every formula of the module, PURE (no Bukkit) so unit tests and self-tests check the research
 * numbers against the code that ships.
 */
public final class PMath {

    private PMath() {}

    // ---- SkyBlock Level (research 02 section 6.1) -----------------------------------------------------

    /**
     * The SkyBlock Level a feature gate dial asks for, so a level reward like "Access to the Bazaar" is listed at the
     * level the gate really uses (final verification 2026-10-04: the owner set {@code bazaar.access-requirement} to
     * Level 5 and the Level 7 level-up box still announced "Access to the Bazaar"). Reads what an admin typed the
     * way the bazaar's own gate does: a bare number N or {@code sblevel:N} = Level N; blank / none / off / open /
     * 0 = everyone ({@code -1}: no level reward to show); anything else (another requirement key) keeps
     * {@code fallback}, since no level can express it.
     */
    public static int gateLevel(String raw, int fallback) {
        if (raw == null) return fallback;
        String t = raw.trim();
        while (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))))
            t = t.substring(1, t.length() - 1).trim();
        String lc = t.toLowerCase(java.util.Locale.ROOT);
        if (lc.isEmpty() || lc.equals("none") || lc.equals("off") || lc.equals("open") || lc.equals("always")) return -1;
        if (lc.startsWith("sblevel:")) lc = lc.substring("sblevel:".length()).trim();
        if (!lc.isEmpty() && lc.chars().allMatch(Character::isDigit)) {
            try {
                long n = Long.parseLong(lc);
                return n <= 0 ? -1 : (int) Math.min(Integer.MAX_VALUE, n);
            } catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }

    /** 100 SB XP per level, flat. */
    public static int level(double xp, int xpPerLevel) {
        if (xpPerLevel <= 0) xpPerLevel = 100;
        return (int) Math.floor(Math.max(0, xp) / xpPerLevel);
    }

    /** XP into the current level. */
    public static double intoLevel(double xp, int xpPerLevel) {
        if (xpPerLevel <= 0) xpPerLevel = 100;
        return Math.max(0, xp) - (double) level(xp, xpPerLevel) * xpPerLevel;
    }

    /** SkyBlock XP still needed to reach {@code targetLevel} (0 when already there). */
    public static int xpToReach(double xp, int targetLevel, int xpPerLevel) {
        if (xpPerLevel <= 0) xpPerLevel = 100;
        double need = (double) Math.max(0, targetLevel) * xpPerLevel - Math.max(0, xp);
        return need <= 0 ? 0 : (int) Math.ceil(need);
    }

    /**
     * Award keys whose module already shows "+N SkyBlock XP" in its own line or box at the moment it awards
     * (checked against each caller 2026-10-04): new minion line, District Guide / Target Practice / Dojo belt /
     * Crimson rank / Commission milestone / fossil / HotM / HotF / collection / Catacombs / crop milestone /
     * Garden level / visitor milestone boxes, and the Bestiary tier / milestone boxes (wave-4 endgame playtest: every
     * new family printed the box's "+1 SkyBlock XP" and then "+1 SkyBlock XP (Bestiary)"). Dial {@code progression.sblevel.quiet-prefixes} edits it live.
     */
    public static final String DEFAULT_QUIET_AWARDS = "minion:,hub_guide:,hub_practice:,crimson:belt:,crimson:rank:,"
            + "mining:commission_milestone:,mining:fossil:,hotm:tier:,hotf:,collection:,catacombs:,"
            + "crop:milestone:,garden:level:,visitor:,bestiary:,island_guide:";

    /**
     * Does an award key start with one of the comma-separated prefixes (case-insensitive, blanks ignored)?
     * Those awards come from modules that already print "+N SkyBlock XP" in their own message or box, so the
     * generic "+N SkyBlock XP (reason)" line would say it twice (wave-3 playtest: a new minion printed both).
     */
    public static boolean quietAward(String key, String prefixes) {
        if (key == null || prefixes == null || prefixes.isBlank()) return false;
        String k = key.toLowerCase(java.util.Locale.ROOT);
        for (String raw : prefixes.split(",")) {
            String pre = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (!pre.isEmpty() && k.startsWith(pre)) return true;
        }
        return false;
    }

    /** +5 Health per level. */
    public static double levelHealth(int level, double perLevel) { return Math.max(0, level) * perLevel; }

    /** +1 Strength per 5 levels. */
    public static int levelStrength(int level, int everyLevels) {
        if (everyLevels <= 0) return 0;
        return Math.max(0, level) / everyLevels;
    }

    private static final String[] LEVEL_COLORS = {
            "&7", "&f", "&e", "&a", "&2", "&b", "&3", "&9", "&d", "&5", "&6", "&c", "&4"
    };

    /** Bracket colour: a new colour every 40 levels (research 02 section 6.2). */
    public static String levelColor(int level) {
        int i = Math.min(LEVEL_COLORS.length - 1, Math.max(0, level) / 40);
        return LEVEL_COLORS[i];
    }

    /** "&8[&b212&8]" */
    public static String levelBracket(int level) { return "&8[" + levelColor(level) + Math.max(0, level) + "&8]"; }

    // ---- Bestiary (research 08 section 6.1, NEU bestiary brackets) ---------------------------------------

    /** Family tier for a kill count: thresholds are cumulative kills for tiers 1..n, capped at maxTier. */
    public static int bestiaryTier(long kills, List<Integer> thresholds, int maxTier) {
        if (thresholds == null) return 0;
        int lim = Math.min(thresholds.size(), Math.max(0, maxTier));
        int t = 0;
        for (int i = 0; i < lim; i++) {
            if (kills >= thresholds.get(i)) t = i + 1; else break;
        }
        return t;
    }

    /** Kills needed for the next tier, or -1 when maxed. */
    public static long bestiaryNext(int tier, List<Integer> thresholds, int maxTier) {
        if (thresholds == null || tier >= maxTier || tier >= thresholds.size()) return -1;
        return thresholds.get(tier);
    }

    /** Max tier of a family: how many thresholds fit under its kill cap (at least 1). */
    public static int bestiaryMaxTier(List<Integer> thresholds, int cap) {
        int n = 0;
        for (int t : thresholds) if (t <= cap) n++;
        return Math.max(1, n);
    }

    /** One milestone per 10 family tiers across the whole bestiary. */
    public static int bestiaryMilestone(int totalTiers) { return Math.max(0, totalTiers) / 10; }

    /**
     * Magic Find against one family at {@code tier} (research 08 6.1: "+70 Magic Find (vs that mob)" at Tier XX).
     * Tiers 1..{@code split} pay {@code early} each, later tiers {@code late} each: with the defaults 2 / 5 / 10 that
     * is 20 at Tier X and exactly 70 at Tier XX, small while a family is new and worth chasing at the top.
     */
    public static double bestiaryMagicFind(int tier, double early, double late, int split) {
        int t = Math.max(0, tier);
        int s = Math.max(0, split);
        return Math.min(t, s) * early + Math.max(0, t - s) * late;
    }

    /** "+X% coins vs that mob": {@code perTier} percent a tier (research: about +40% at Tier XX, so 2). */
    public static double bestiaryCoinPct(int tier, double perTier) { return Math.max(0, tier) * Math.max(0, perTier); }

    /** Coins after the bestiary bonus (never negative, never NaN). */
    public static double scaledCoins(double coins, double pct) {
        if (!(coins > 0) || !Double.isFinite(coins)) return coins;   // leave odd values exactly as they were
        double out = coins * (1 + Math.max(0, pct) / 100.0);
        return Double.isFinite(out) ? out : coins;
    }

    // ---- SkyBlock Level perks (research 02 6.2) ----------------------------------------------------------

    /** Levels at which the Tome of Progress is handed over (25) and then upgrades a rarity step. */
    public static final int[] TOME_LEVELS = {25, 75, 150, 225, 300, 400};

    /** How many tome steps a level has reached: 0 = not earned, 1 = Common (L25), 2 = Uncommon ... 6 = Mythic. */
    public static int tomeStage(int level) {
        int n = 0;
        for (int l : TOME_LEVELS) if (level >= l) n++;
        return n;
    }

    // ---- Island Guides (research 03 2.2) ----------------------------------------------------------------

    /** The step index after one more completion, clamped to {@code size} (= finished). */
    public static int guideAdvance(int step, int size) { return Math.max(0, Math.min(size, step + 1)); }

    /** Does a mob id match a kill step's prefixes (empty list = any mob)? */
    public static boolean killMatches(String mobId, String family, List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) return true;
        for (String pre : prefixes) {
            if (pre == null || pre.isBlank()) continue;
            if (mobId != null && mobId.startsWith(pre)) return true;
            if (family != null && family.equals(pre)) return true;
        }
        return false;
    }

    /** Does a gathered id match a gather step's substrings (empty list = anything)? */
    public static boolean gatherMatches(String what, List<String> subs) {
        if (subs == null || subs.isEmpty()) return true;
        if (what == null) return false;
        String w = what.toUpperCase(java.util.Locale.ROOT);
        for (String s : subs) if (s != null && !s.isBlank() && w.contains(s.toUpperCase(java.util.Locale.ROOT))) return true;
        return false;
    }

    // ---- Fairy souls ----------------------------------------------------------------------------------

    /** Exchanges the player can make now: one per 5 found souls not yet exchanged. */
    public static int fairyExchangesAvailable(int found, int exchanged) {
        return Math.max(0, found / 5 - Math.max(0, exchanged));
    }

    /** Stat totals after {@code exchanges} exchanges: {health, defense, strength, speed}. */
    public static double[] fairyStats(int exchanges, double healthEach, double defenseEach,
                                      int strengthEvery, int speedEvery) {
        int n = Math.max(0, exchanges);
        double hp = n * healthEach;
        double def = n * defenseEach;
        double str = strengthEvery <= 0 ? 0 : n / strengthEvery;
        double spd = speedEvery <= 0 ? 0 : n / speedEvery;
        return new double[]{hp, def, str, spd};
    }

    // ---- Objective chain -------------------------------------------------------------------------------

    /** The current step: the first id neither done nor skipped (what the sidebar shows), or null when through. */
    public static String currentObjective(List<String> chain, Collection<String> done, Collection<String> skipped) {
        for (String id : chain) if (!done.contains(id) && !skipped.contains(id)) return id;
        return null;
    }

    /**
     * Whether completing {@code id} now is credited at once (box + reward): only when the chain has reached it,
     * i.e. it is the current step or behind it (a step the player skipped and went back to). A step AHEAD of the
     * current one is recorded as "early" and credited when the chain gets there, so an "OBJECTIVE COMPLETE" box
     * never fires for a step the sidebar is not showing (playtest-endgame: "Visit The End" completing while the
     * sidebar still said "Talk to your Island Helper"). Ids outside the chain are always credited (recorded only).
     */
    public static boolean objectiveReached(List<String> chain, Collection<String> done, Collection<String> skipped, String id) {
        int at = chain.indexOf(id);
        if (at < 0) return true;
        String cur = currentObjective(chain, done, skipped);
        return cur == null || at <= chain.indexOf(cur);
    }

    /**
     * The steps done early that the chain has now reached: starting at the current step, the consecutive run
     * of ids in {@code early} (skipped steps are passed over, as the chain does). Crediting them in this order
     * walks the pointer up to the first step the player has really not done.
     */
    public static List<String> objectiveCatchUp(List<String> chain, Collection<String> done, Collection<String> skipped,
                                                Collection<String> early) {
        List<String> run = new ArrayList<>();
        for (String id : chain) {
            if (done.contains(id) || skipped.contains(id)) continue;
            if (!early.contains(id)) break;
            run.add(id);
        }
        return run;
    }

    // ---- Museum ----------------------------------------------------------------------------------------

    /** Museum milestones: thresholds on museum SkyBlock XP. Returns how many are reached. */
    public static int museumMilestone(int museumXp, int[] thresholds) {
        int n = 0;
        for (int t : thresholds) if (museumXp >= t) n++;
        return n;
    }

    // ---- SkyBlock XP guide (wave-5 regression: early pacing) ----------------------------------------------

    /**
     * One concrete next source of SkyBlock XP: {@code remaining} is the share of that step still to do
     * (0 = claim it now, 1 = not started), {@code xp} what it pays. Built by {@code XpGuide} from live data.
     */
    public record XpTip(String kind, String label, int xp, double remaining) {}

    /**
     * The guide's order. Wave-5 regression: 15 objectives in 25 minutes earned 86 SkyBlock XP, 75 of them from the
     * objectives, because each step asked for a little of everything (10 wheat, 5 cobblestone, one log) and stopped
     * just short of the first skill level (50 XP) and collection tier (50 items) where the reference's early XP is.
     * So: claimable / nearly done first, more XP breaks ties, at most {@code perKind} of one kind (else the list is
     * all collections), nothing that pays 0.
     */
    public static List<XpTip> rankXpTips(List<XpTip> tips, int limit, int perKind) {
        List<XpTip> pool = new ArrayList<>();
        if (tips != null) for (XpTip t : tips) {
            if (t == null || t.xp() <= 0 || Double.isNaN(t.remaining())) continue;
            pool.add(new XpTip(t.kind(), t.label(), t.xp(), Math.max(0, Math.min(1, t.remaining()))));
        }
        pool.sort((a, b) -> {
            int c = Double.compare(a.remaining(), b.remaining());
            if (c != 0) return c;
            c = Integer.compare(b.xp(), a.xp());
            return c != 0 ? c : String.valueOf(a.label()).compareTo(String.valueOf(b.label()));
        });
        List<XpTip> out = new ArrayList<>();
        java.util.Map<String, Integer> per = new java.util.HashMap<>();
        for (XpTip t : pool) {
            if (out.size() >= Math.max(0, limit)) break;
            int n = per.getOrDefault(t.kind(), 0);
            if (perKind > 0 && n >= perKind) continue;
            per.put(t.kind(), n + 1);
            out.add(t);
        }
        return out;
    }

    /** Share of a threshold step still to go: from {@code floor} (last threshold) to {@code next}, having {@code have}. */
    public static double remainingShare(double have, double floor, double next) {
        if (next <= floor) return have >= next ? 0 : 1;
        return Math.max(0, Math.min(1, (next - have) / (next - floor)));
    }

    /** Research 02 / NEU sblevels: SkyBlock XP for reaching skill level {@code level} (5 / 10 / 20 / 30 by bracket). */
    public static int skillLevelSbXp(int level) {
        if (level <= 0) return 0;
        if (level <= 10) return 5;
        if (level <= 25) return 10;
        if (level <= 50) return 20;
        return 30;
    }

    /** Milestone thresholds on museum XP (TUNE: the reference's exact milestone table is not in the research). */
    public static final int[] MUSEUM_MILESTONES = {5, 15, 30, 50, 75, 100, 150, 200, 300, 400, 500, 650, 800,
            1000, 1250, 1500, 2000, 2500, 3000, 3500};
}
