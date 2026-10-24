package com.skyclone.mod.enchanting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The two Experimentation add-ons as pure state machines (research 08 §3.4).
 * <ul>
 *   <li><b>Chronomatron</b>: a sequence of coloured keys flashes; repeat it. Each cleared round adds
 *       one note. One mistake ends the game.</li>
 *   <li><b>Ultrasequencer</b>: numbers 1..n appear in random slots, then hide; click the slots in
 *       ascending order. Each cleared round adds one number. One mistake ends the game.</li>
 * </ul>
 * Both expose: {@link #sequence} (what to show), {@link #input} (a click), {@link #roundsCleared}.
 */
public final class SequenceGame {

    public enum Mode { CHRONOMATRON, ULTRASEQUENCER }

    public enum Phase { SHOWING, INPUT, WON, LOST }

    public final Mode mode;
    public final int maxRounds;
    /** Chronomatron: number of keys. Ultrasequencer: number of slots in the grid. */
    public final int width;
    private final Random rnd;

    /** Chronomatron: key indexes in order. Ultrasequencer: slot index of number (i+1). */
    public final List<Integer> sequence = new ArrayList<>();
    public Phase phase = Phase.SHOWING;
    public int roundsCleared;
    /** Position in the sequence the player must click next. */
    public int expected;

    public SequenceGame(Mode mode, int maxRounds, int width, Random rnd) {
        this.mode = mode;
        this.maxRounds = Math.max(1, maxRounds);
        this.width = Math.max(2, width);
        this.rnd = rnd;
        nextRound();
    }

    /** Ultrasequencer starts with 3 numbers (round 1), Chronomatron with 1 note; each round adds one. */
    public int lengthOfRound(int round) { return mode == Mode.ULTRASEQUENCER ? Math.min(width, round + 2) : round; }

    private void nextRound() {
        int len = lengthOfRound(roundsCleared + 1);
        if (mode == Mode.CHRONOMATRON) {
            while (sequence.size() < len) sequence.add(rnd.nextInt(width));
        } else {
            sequence.clear();
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < width; i++) slots.add(i);
            Collections.shuffle(slots, rnd);
            sequence.addAll(slots.subList(0, len));
        }
        expected = 0;
        phase = Phase.SHOWING;
    }

    /** The controller calls this when the sequence has been shown. */
    public void startInput() { if (phase == Phase.SHOWING) phase = Phase.INPUT; }

    /** A click on key / slot {@code what}. Returns true when it was correct. */
    public boolean input(int what) {
        if (phase != Phase.INPUT) return false;
        if (sequence.get(expected) != what) { phase = Phase.LOST; return false; }
        expected++;
        if (expected >= sequence.size()) {
            roundsCleared++;
            if (roundsCleared >= maxRounds) phase = Phase.WON;
            else nextRound();
        }
        return true;
    }

    public boolean over() { return phase == Phase.WON || phase == Phase.LOST; }

    /** Bonus Superpairs clicks earned: +1 at a third, two thirds and all of the max rounds (research 08 clone). */
    public static int bonusClicks(int rounds, int max) {
        if (max <= 0) return 0;
        return Math.min(3, rounds * 3 / max);
    }
}
