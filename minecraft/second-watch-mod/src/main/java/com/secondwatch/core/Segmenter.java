package com.secondwatch.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cuts a continuous stream of 20 ms voice frames into things a person said.
 *
 * <p>This is the join between the microphone and the whole design, and getting
 * it wrong is not subtle. Cut too eagerly and the phantom replays half-words
 * that read as a glitch; cut too late and it replays ninety seconds of two
 * people arguing about iron. Neither is frightening - the target is a sentence,
 * because a sentence is what a person says when they walk round a corner.
 *
 * <p>Pure, so the boundaries can be tested against a scripted conversation in
 * milliseconds rather than by talking into a headset and hoping.
 */
public final class Segmenter {

    /** Simple Voice Chat frames are 20 ms. */
    public static final long FRAME_MS = 20L;

    /**
     * A pause longer than this ends the sentence.
     *
     * <p>400 ms is roughly the gap people leave between sentences and roughly
     * double the gap they leave between words, which is the whole reason this
     * number can exist at all.
     */
    public static final long SILENCE_GAP_MS = 400L;

    /**
     * Nobody's phantom gets to monologue. A long unbroken stretch is cut here
     * regardless of pauses.
     */
    public static final long MAX_UTTERANCE_MS = 12_000L;

    /**
     * One thing somebody said.
     *
     * @param speakerId  who
     * @param startMs    run-relative start
     * @param durationMs how long
     * @param frames     how many 20 ms frames it contains
     */
    public record Segment(String speakerId, long startMs, long durationMs, int frames) {
    }

    private static final class Open {
        long startMs;
        long lastFrameMs;
        int frames;
    }

    private final Map<String, Open> open = new LinkedHashMap<>();

    /**
     * Feed one frame of speech.
     *
     * @return the segment that this frame just ENDED, if any - a frame arriving
     *         after a long silence closes whatever came before it
     */
    public Optional<Segment> onFrame(String speakerId, long nowMs) {
        Open o = open.get(speakerId);

        if (o == null) {
            o = new Open();
            o.startMs = nowMs;
            o.lastFrameMs = nowMs;
            o.frames = 1;
            open.put(speakerId, o);
            return Optional.empty();
        }

        long gap = nowMs - o.lastFrameMs;
        long lengthSoFar = nowMs - o.startMs;

        if (gap > SILENCE_GAP_MS || lengthSoFar >= MAX_UTTERANCE_MS) {
            Segment done = close(speakerId, o);
            Open next = new Open();
            next.startMs = nowMs;
            next.lastFrameMs = nowMs;
            next.frames = 1;
            open.put(speakerId, next);
            return Optional.of(done);
        }

        o.lastFrameMs = nowMs;
        o.frames++;
        return Optional.empty();
    }

    /**
     * Close anything that has gone quiet. Must be called on a timer as well as
     * on frames - the last sentence before somebody stops talking has no
     * following frame to close it, and without this it would sit open forever
     * and never become usable.
     */
    public List<Segment> flushIdle(long nowMs) {
        List<Segment> done = new ArrayList<>();
        List<String> finished = new ArrayList<>();

        for (Map.Entry<String, Open> e : open.entrySet()) {
            if (nowMs - e.getValue().lastFrameMs > SILENCE_GAP_MS) {
                done.add(close(e.getKey(), e.getValue()));
                finished.add(e.getKey());
            }
        }
        for (String id : finished) open.remove(id);
        return done;
    }

    private static Segment close(String speakerId, Open o) {
        long duration = (o.lastFrameMs - o.startMs) + FRAME_MS;
        return new Segment(speakerId, o.startMs, duration, o.frames);
    }

    public int openCount() {
        return open.size();
    }
}
