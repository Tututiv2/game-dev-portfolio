package com.skyclone.mod.extras;

import java.util.ArrayList;
import java.util.List;

/**
 * The harp minigame as pure state (no Bukkit), research 08 section 6.3: seven lanes, notes fall one row
 * per beat through rows 0-4, and a note sitting in the bottom row (4) must be struck by clicking its lane's
 * key in the row below. A note that falls past the bottom row is a miss; a key struck with no note on it
 * is a wrong note. Both are mistakes; more than {@code maxMistakes} fails the song. Zero is a perfect play.
 */
final class HarpGame {

    static final int LANES = 7;
    static final int ROWS = 5;
    static final int HIT_ROW = ROWS - 1;
    private static final String DEGREES = "CDEFGAB";
    private static final int[] SEMIS = {0, 2, 4, 5, 7, 9, 11};

    enum Press { HIT, WRONG, IGNORED }

    record Note(int beat, int lane, float pitch) {}

    final List<Note> notes;
    private final boolean[] struck;
    private final int maxMistakes;
    private final int lastBeat;
    private int beat = -1;
    int hits, misses, wrong;
    boolean finished, failed;

    HarpGame(String notation, int maxMistakes) {
        this.notes = parse(notation);
        this.struck = new boolean[notes.size()];
        this.maxMistakes = Math.max(0, maxMistakes);
        this.lastBeat = notes.isEmpty() ? 0 : notes.get(notes.size() - 1).beat();
    }

    /** One token per beat: a pitch such as {@code E4} / {@code F#5}, or {@code -} for a rest. */
    static List<Note> parse(String notation) {
        List<Note> out = new ArrayList<>();
        if (notation == null) return out;
        String[] t = notation.trim().split("\\s+");
        for (int i = 0; i < t.length; i++) {
            String s = t[i];
            if (s.isEmpty() || s.equals("-")) continue;
            int deg = DEGREES.indexOf(s.charAt(0));
            if (deg < 0) throw new IllegalArgumentException("bad note '" + s + "'");
            boolean sharp = s.length() > 2 && s.charAt(1) == '#';
            int octave;
            try { octave = Integer.parseInt(s.substring(sharp ? 2 : 1)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("bad note '" + s + "'"); }
            int midi = 12 * (octave + 1) + SEMIS[deg] + (sharp ? 1 : 0);
            out.add(new Note(i, deg, pitch(midi)));
        }
        return out;
    }

    /** Note-block pitch: F#4 (midi 66) = 1.0, one octave either side = 0.5 / 2.0. */
    static float pitch(int midi) {
        double p = Math.pow(2, (midi - 66) / 12.0);
        return (float) Math.max(0.5, Math.min(2.0, p));
    }

    int beat() { return beat; }
    int total() { return notes.size(); }
    int mistakes() { return misses + wrong; }
    boolean completed() { return finished && !failed; }
    boolean perfect() { return completed() && mistakes() == 0; }
    int accuracy() { return notes.isEmpty() ? 100 : (int) Math.round(100.0 * hits / notes.size()); }

    /** Advance one beat. Notes leaving the bottom row unstruck become misses. */
    void step() {
        if (finished) return;
        beat++;
        for (int i = 0; i < notes.size(); i++) {
            if (!struck[i] && beat - notes.get(i).beat() == ROWS) misses++;
        }
        if (mistakes() > maxMistakes) { failed = true; finished = true; return; }
        if (beat >= lastBeat + ROWS) finished = true;
    }

    /** Index of the unstruck note drawn at (row, lane) right now, or -1. */
    int noteAt(int row, int lane) {
        for (int i = 0; i < notes.size(); i++) {
            Note n = notes.get(i);
            if (!struck[i] && n.lane() == lane && beat - n.beat() == row) return i;
        }
        return -1;
    }

    /** The player strikes a lane's key. */
    Press press(int lane) {
        if (finished || beat < 0 || lane < 0 || lane >= LANES) return Press.IGNORED;
        int i = noteAt(HIT_ROW, lane);
        if (i >= 0) {
            struck[i] = true;
            hits++;
            return Press.HIT;
        }
        wrong++;
        if (mistakes() > maxMistakes) { failed = true; finished = true; }
        return Press.WRONG;
    }

    /** Pitch of the note that would be struck in this lane now (for the sound), or the lane's default. */
    float pitchAt(int lane) {
        int i = noteAt(HIT_ROW, lane);
        if (i >= 0) return notes.get(i).pitch();
        return pitch(60 + SEMIS[Math.max(0, Math.min(6, lane))]);
    }
}
