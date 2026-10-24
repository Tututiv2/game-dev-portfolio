package com.secondwatch.core;

/**
 * One thing a player actually said, as recorded off the voice channel.
 *
 * <p>There is no text field and there never will be. The mod does not know what
 * you said and does not need to - every rule in this project is expressed in
 * terms of when a line was spoken, how long it was, and whether it followed
 * someone else. Refusing to transcribe is a design decision, not a shortcut: it
 * keeps the phantom honest (it can only ever hand back real audio) and it means
 * no player's speech is ever turned into text and stored.
 *
 * @param id         monotonic id, unique within a run
 * @param speakerId  who said it
 * @param startMs    run-relative time the line began
 * @param durationMs how long it ran
 * @param wasReply   true if it began shortly after somebody else stopped talking
 * @param audioRef   opaque handle to the stored audio frames
 */
public record Utterance(
        long id,
        String speakerId,
        long startMs,
        long durationMs,
        boolean wasReply,
        String audioRef) {

    public Utterance {
        if (speakerId == null || speakerId.isBlank()) {
            throw new IllegalArgumentException("utterance needs a speaker");
        }
        if (durationMs <= 0) {
            throw new IllegalArgumentException("utterance needs a positive duration");
        }
        if (startMs < 0) {
            throw new IllegalArgumentException("utterance cannot start before the run");
        }
    }

    public long endMs() {
        return startMs + durationMs;
    }

    public long ageMsAt(long nowMs) {
        return nowMs - endMs();
    }

    /**
     * Lines shorter than this are grunts, coughs and half-words. Replaying one
     * is not frightening, it is a glitch - and a glitch reads as a bug, which is
     * the one thing that breaks the spell.
     */
    public static final long MIN_USABLE_MS = 700L;

    public boolean usableLength() {
        return durationMs >= MIN_USABLE_MS;
    }
}
