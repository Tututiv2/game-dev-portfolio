package com.skyclone.mod.kuudra;

import java.util.ArrayList;
import java.util.List;

/**
 * Cutscene timelines. A server cannot move a player's camera, so a "cutscene" here is a timed script of titles,
 * NPC lines, sounds, particles and display entities while the crew stands frozen (transient attribute modifiers:
 * no walking, no jumping, no damage) facing what matters. {@link Scenes} performs the beats; this class only says
 * WHEN, so the timing is unit-tested without a server.
 * <p>
 * Ticks are server ticks; a hunt steps every 5, so every beat sits on a multiple of 5.
 */
public final class KScript {

    private KScript() {}

    public static final String OPENING = "opening", VICTORY = "victory";

    public record Beat(int tick, String id) {}

    /**
     * @param freeze    the crew cannot move (or be hurt) while it plays
     * @param skippable every hunter crouching skips it (the scene's {@code end} still runs, so the world is left right)
     */
    public record Scene(String id, int length, boolean freeze, boolean skippable, List<Beat> beats) {

        /** Beats with {@code from < tick <= to}, in order. */
        public List<Beat> between(int from, int to) {
            List<Beat> out = new ArrayList<>();
            for (Beat b : beats) if (b.tick() > from && b.tick() <= to) out.add(b);
            return out;
        }

        public int at(String beat) {
            for (Beat b : beats) if (b.id().equals(beat)) return b.tick();
            return -1;
        }
    }

    /** Server ticks the beast takes to surface (Run.riseTick: 20 hunt steps of 5 ticks). */
    public static final int RISE_TICKS = 100;

    /**
     * The crew's first sight of the beast (12.5 s): the hollow goes quiet, the lake churns, four tentacles break the
     * surface, the beast heaves itself up, roars, and its name fills the screen. Then the supplies phase starts.
     */
    public static Scene opening() {
        return new Scene(OPENING, 250, true, true, List.of(
                new Beat(5, "frame"),        // face the lake, "LEVIATHAN'S HOLLOW"
                new Beat(15, "brask1"),
                new Beat(40, "churn"),       // the surface boils
                new Beat(60, "tentacles"),   // four arms break the surface
                new Beat(85, "brask2"),
                new Beat(95, "sway"),
                new Beat(100, "rise"),       // the beast surfaces (RISE_TICKS)
                new Beat(150, "brask3"),
                new Beat(200, "roar"),       // RISE done
                new Beat(220, "name"),       // "THE MAGMA LEVIATHAN"
                new Beat(250, "end")));
    }

    /** The beast goes under for good (4 s): its carcass sinks into the lake in bursts of fire. */
    public static Scene victory() {
        return new Scene(VICTORY, 80, true, true, List.of(
                new Beat(5, "carcass"),
                new Beat(10, "sink"),
                new Beat(25, "burst"),
                new Beat(45, "burst"),
                new Beat(65, "burst"),
                new Beat(80, "end")));
    }

    public static Scene byId(String id) {
        if (OPENING.equals(id)) return opening();
        if (VICTORY.equals(id)) return victory();
        return null;
    }

    /**
     * Full scene or the short cut: the full opening plays when cutscenes are on and someone in the crew has never
     * watched it (or the repeat dial is on). Everyone else gets the old 5 s countdown with the beast surfacing.
     */
    public static boolean playFull(boolean enabled, boolean repeat, List<Boolean> crewHasSeen) {
        if (!enabled) return false;
        if (repeat) return true;
        for (Boolean seen : crewHasSeen) if (seen == null || !seen) return true;
        return false;
    }

    /** Crouch-to-skip: every hunter still in the hunt has voted. */
    public static boolean skipped(int votes, int crew) { return crew > 0 && votes >= crew; }
}
