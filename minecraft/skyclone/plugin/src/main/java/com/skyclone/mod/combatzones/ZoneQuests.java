package com.skyclone.mod.combatzones;

import java.util.List;

/**
 * Wanderer Tavi's kill quests in The End (the reference's Lone Adventurer: "Void Sword; kill-Endermen tasks",
 * research 03 sec 14.5). The research names the giver and that the tasks are kills of End mobs; the ladder below
 * (what, how many, what it pays) is designed here and every number is TUNE. Pure Java: unit-tested.
 *
 * <p>The quests run in order. A kill counts when the mob id starts with one of a quest's prefixes and the player
 * was its killer (core's MobKill killer = last player hit within 15 s). Finishing pays coins and Combat XP and,
 * for some, an item; the last one also unlocks Tavi's discount on the Void Sword.
 */
public final class ZoneQuests {

    private ZoneQuests() {}

    public record Quest(String id, String title, String what, List<String> prefixes, int count,
                        double coins, double combatXp, String item, int itemAmount) {
        public boolean counts(String mobId) {
            if (mobId == null) return false;
            for (String p : prefixes) if (mobId.startsWith(p)) return true;
            return false;
        }
    }

    public static final List<Quest> QUESTS = List.of(
            new Quest("endermen", "Thin the Herd", "Endermen", List.of("end_enderman_"), 50, 5_000, 1_000, null, 0),
            new Quest("endermites", "Pest Control", "Endermites", List.of("end_endermite_", "end_nest_endermite"), 25, 5_000, 750, null, 0),
            new Quest("defenders", "Crack the Obsidian", "Obsidian Defenders", List.of("end_obsidian_defender"), 15, 10_000, 1_500, null, 0),
            new Quest("seers", "Blind the Seers", "Seers", List.of("end_seer"), 15, 10_000, 1_500, null, 0),
            new Quest("zealots", "Zealous Work", "Zealots", List.of("end_zealot_5"), 100, 20_000, 3_000, "REMNANT_OF_THE_EYE", 1),
            new Quest("bruisers", "Into the Hollow", "Zealot Bruisers", List.of("end_zealot_bruiser"), 25, 40_000, 5_000, "REMNANT_OF_THE_EYE", 2),
            new Quest("dragon", "The Big One", "Ender Dragon (any share)", List.of("end_dragon_"), 1, 75_000, 0, null, 0));

    /** The quest at {@code index}, or null when the chain is finished. */
    public static Quest at(int index) { return index >= 0 && index < QUESTS.size() ? QUESTS.get(index) : null; }

    /**
     * Applies one kill: returns the new progress (capped at the quest's count). The caller pays out and moves to the
     * next quest when the returned value reaches {@code count}.
     */
    public static int progress(Quest q, int current, String mobId) {
        if (q == null || !q.counts(mobId)) return current;
        return Math.min(q.count(), Math.max(0, current) + 1);
    }

    public static boolean allDone(int index) { return index >= QUESTS.size(); }

    /** Price of the Void Sword at Tavi (TUNE): full price, then {@code discountPct}% off once every quest is done. */
    public static double voidSwordPrice(double base, double discountPct, boolean chainDone) {
        double p = Math.max(0, base);
        return chainDone ? Math.round(p * (1 - Math.max(0, Math.min(100, discountPct)) / 100.0)) : p;
    }
}
