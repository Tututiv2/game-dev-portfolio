package com.skyclone.mod.kuudra;

import java.util.List;

/**
 * The intro questline "The Leviathan's Wake" (reference: Elle's introduction, research 03 sec 15.6 "Basic | Elle
 * introduction" and 15.8 "Elle | Kuudra intro"). Dockhand Nell (crimson's {@code leviathan_dock} NPC, who wears the
 * reference Elle's skin) stands in for Elle. The reference publishes the gate, not the steps, so the steps are ours in
 * its spirit: meet the dockhand, stand with a faction, prove you can fight in the heat, report back (she hands over a
 * Basic key so the first paid chest can be opened), then bring the beast down once.
 * <p>
 * Pure (no Bukkit): {@link #advance} is the whole state machine, unit-tested. Steps only ever move forward.
 */
public final class KIntro {

    private KIntro() {}

    public static final String NAME = "The Leviathan's Wake";

    public static final int MEET = 0, PLEDGE = 1, CULL = 2, REPORT = 3, HUNT = 4, DONE = 5;

    /** One step: what the quest log and the sidebar say about it. {@code talk} steps finish at Nell. */
    public record Step(int index, String id, String title, String objective, boolean talk) {}

    public static final List<Step> STEPS = List.of(
            new Step(MEET, "meet", "A Boat for the Hollow", "Talk to Dockhand Nell at the Leviathan dock", true),
            new Step(PLEDGE, "pledge", "Colours of the Isle", "Pledge yourself to a Crimson Isle faction", false),
            new Step(CULL, "cull", "Stand the Heat", "Slay {n} creatures on the Crimson Isle", false),
            new Step(REPORT, "report", "Back to the Dock", "Report to Dockhand Nell", true),
            new Step(HUNT, "hunt", "Into the Hollow", "Bring down the Magma Leviathan (any tier)", false));

    /** The step a player is on (DONE past the last). */
    public static Step step(int index) { return index >= 0 && index < STEPS.size() ? STEPS.get(index) : null; }

    /**
     * Auto-progress from facts: a pledge finishes PLEDGE, enough kills finish CULL, a clear finishes HUNT. Talk steps
     * (MEET, REPORT) only move through {@link #talk}. Never moves backwards; a value out of range is clamped.
     */
    public static int advance(int step, boolean pledged, int kills, int killsNeeded, int clears) {
        int s = Math.max(MEET, Math.min(DONE, step));
        if (s == PLEDGE && pledged) s = CULL;
        if (s == CULL && kills >= Math.max(0, killsNeeded)) s = REPORT;
        if (s == HUNT && clears > 0) s = DONE;
        return s;
    }

    /** Talking to Nell finishes a talk step (MEET -> PLEDGE, REPORT -> HUNT); any other step is unchanged. */
    public static int talk(int step) {
        if (step == MEET) return PLEDGE;
        if (step == REPORT) return HUNT;
        return step;
    }

    /** The Basic hunt opens once Nell has sent you out (REPORT done). */
    public static boolean unlocked(int step) { return step >= HUNT; }

    /** A hunter who cleared the Leviathan before the questline existed has nothing left to prove. */
    public static int migrate(int step, int clears) { return clears > 0 ? DONE : step; }

    /** The objective line for a step, with live kill progress on CULL; null when the questline is done. */
    public static String objective(int step, int kills, int killsNeeded) {
        Step s = step(step);
        if (s == null) return null;
        String o = s.objective().replace("{n}", String.valueOf(Math.max(0, killsNeeded)));
        return step == CULL ? o + " (" + Math.min(kills, killsNeeded) + "/" + killsNeeded + ")" : o;
    }

    /** Nell's lines for a step when the player talks to her (the step BEFORE the talk is applied). */
    public static List<String> lines(int step, boolean pledged) {
        return switch (step) {
            case MEET -> List.of(
                    "&fAnother face on my dock. Most turn round once they feel the heat coming off the lake.",
                    "&fUnder that lava sleeps the &cMagma Leviathan&f. When it stirs, crews row out and put it back down.",
                    "&fI don't put just anyone in a boat, though.",
                    pledged ? "&fYou already wear a faction's colours - good. Now show me you can fight out here."
                            : "&fFirst, stand with one of the isle's factions. Nobody rows for a stranger.",
                    "&fThen thin out the isle's creatures for me. Come back when your blade's been warm a while.");
            case PLEDGE -> List.of(
                    "&fStill no colours on you? Go see the Mages or the Barbarians - pledge to one of them.",
                    "&fCome back once you belong somewhere.");
            case CULL -> List.of(
                    "&fNot yet. Keep at the isle's creatures - I'll know when you've had enough heat.");
            case REPORT -> List.of(
                    "&fThere you are. Singed, but standing. That'll do.",
                    "&fHere's how it goes: haul the &6supply crates &fonto the pads, then &ebuild &fthe ballista together.",
                    "&fLoad &cfuel cells&f and fire. Hurt it enough and it drags itself ashore - that's when your blade counts.",
                    "&fWin, and two chests wait: one is free, the other opens with a &aLeviathan Key&f.",
                    "&fTake this one - first hunt's on the dock. Pick &aBasic &fon the board and go.");
            case HUNT -> List.of(
                    "&fThe boats are waiting. &aBasic &fon the board - bring that beast down once and we'll talk.");
            default -> List.of(
                    "&fBack for more? The board's yours. Hotter tiers open as your faction learns to trust you.");
        };
    }
}
