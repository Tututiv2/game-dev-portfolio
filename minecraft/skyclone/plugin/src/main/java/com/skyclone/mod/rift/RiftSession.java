package com.skyclone.mod.rift;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One visit to the Rift. In memory only: a visit ends when you leave, log out or the server stops,
 * which is the reference's rule too (Rift Time does not carry between visits).
 */
final class RiftSession {
    double remaining;
    final long enteredAt = System.currentTimeMillis();
    long motesThisVisit;
    /** Gates passed this visit: once through a time gate you stay through until you leave the Rift. */
    final Set<String> gates = new HashSet<>();
    /** Elixir type -> expiry ms. */
    final Map<String, Long> elixirs = new HashMap<>();
    boolean warned60, warned30, warned10, ejecting;
    // race state
    long raceStart;
    int raceNext;
    // maze state
    long[] maze;
    long mazeRevealUntil;
    // dance state
    int danceRound = -1;
    long danceDeadline;
    int danceColor = -1;
    // parkour room
    boolean inParkour;
    // wave 5: riddle puzzles, the Punchcard Artifact, the cat detector's glow
    final RiftRiddles.State rs = new RiftRiddles.State();
    final Set<java.util.UUID> punched = new HashSet<>();
    long catGlowUntil;

    boolean elixir(String type, long now) {
        Long t = elixirs.get(type);
        return t != null && t > now;
    }
}
