package com.secondwatch.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the join between a microphone and the entire design. If these are wrong
 * the phantom replays half-words or monologues, and both read as a bug rather
 * than as your friend.
 */
class SegmenterTest {

    private static final String A = "tutut";
    private static final String B = "friend";

    /** Speak continuously for {@code ms}, starting at {@code fromMs}. */
    private static List<Segmenter.Segment> speak(Segmenter s, String who, long fromMs, long ms) {
        List<Segmenter.Segment> out = new ArrayList<>();
        for (long t = fromMs; t < fromMs + ms; t += Segmenter.FRAME_MS) {
            s.onFrame(who, t).ifPresent(out::add);
        }
        return out;
    }

    @Test
    void oneSentenceThenSilenceProducesOneSegment() {
        Segmenter s = new Segmenter();
        assertTrue(speak(s, A, 0, 2_000).isEmpty(), "nothing closes while they are still talking");

        List<Segmenter.Segment> done = s.flushIdle(2_000 + Segmenter.SILENCE_GAP_MS + 1);
        assertEquals(1, done.size());

        Segmenter.Segment seg = done.get(0);
        assertEquals(A, seg.speakerId());
        assertEquals(0, seg.startMs());
        assertEquals(2_000, seg.durationMs());
        assertEquals(100, seg.frames());
    }

    /** A pause between sentences is what makes a sentence a sentence. */
    @Test
    void aPauseSplitsTwoSentences() {
        Segmenter s = new Segmenter();
        speak(s, A, 0, 1_000);

        long resume = 1_000 + Segmenter.SILENCE_GAP_MS + 200;
        List<Segmenter.Segment> closed = speak(s, A, resume, 800);

        assertEquals(1, closed.size(), "resuming after a pause must close the first sentence");
        assertEquals(1_000, closed.get(0).durationMs());

        List<Segmenter.Segment> rest = s.flushIdle(resume + 800 + Segmenter.SILENCE_GAP_MS + 1);
        assertEquals(1, rest.size());
        assertEquals(800, rest.get(0).durationMs());
    }

    /** A gap shorter than the threshold is a breath, not a boundary. */
    @Test
    void aShortBreathDoesNotSplit() {
        Segmenter s = new Segmenter();
        speak(s, A, 0, 600);
        List<Segmenter.Segment> closed = speak(s, A, 600 + Segmenter.SILENCE_GAP_MS - 50, 600);

        assertTrue(closed.isEmpty(), "a 350ms breath must not end the sentence");
        assertEquals(1, s.flushIdle(10_000).size());
    }

    /** Nobody's phantom gets to monologue. */
    @Test
    void aVeryLongStretchIsCutAtTheCeiling() {
        Segmenter s = new Segmenter();
        List<Segmenter.Segment> closed = speak(s, A, 0, Segmenter.MAX_UTTERANCE_MS * 2);

        assertFalse(closed.isEmpty(), "an unbroken monologue must still be cut up");
        for (Segmenter.Segment seg : closed) {
            assertTrue(seg.durationMs() <= Segmenter.MAX_UTTERANCE_MS + Segmenter.FRAME_MS,
                    "segment ran to " + seg.durationMs() + "ms");
        }
    }

    /** Two people talking at once must not be spliced into one person. */
    @Test
    void speakersAreKeptApart() {
        Segmenter s = new Segmenter();
        for (long t = 0; t < 1_000; t += Segmenter.FRAME_MS) {
            s.onFrame(A, t);
            s.onFrame(B, t);
        }
        assertEquals(2, s.openCount());

        List<Segmenter.Segment> done = s.flushIdle(1_000 + Segmenter.SILENCE_GAP_MS + 1);
        assertEquals(2, done.size());
        assertTrue(done.stream().anyMatch(x -> x.speakerId().equals(A)));
        assertTrue(done.stream().anyMatch(x -> x.speakerId().equals(B)));
        assertEquals(0, s.openCount(), "flushed segments must not stay open");
    }

    /**
     * The last thing said before somebody goes quiet has no following frame to
     * close it. Without the idle flush it would sit open forever and the phantom
     * would never be able to use it - which is exactly the line you most want it
     * to have.
     */
    @Test
    void theFinalSentenceIsNotLost() {
        Segmenter s = new Segmenter();
        speak(s, A, 0, 1_500);
        assertEquals(1, s.openCount());

        assertTrue(s.flushIdle(1_500 + 100).isEmpty(), "too early - they may still be talking");
        assertEquals(1, s.flushIdle(1_500 + Segmenter.SILENCE_GAP_MS + 1).size());
    }

    /** Segments must be long enough for the corpus to accept them. */
    @Test
    void ordinarySentencesSurviveTheCorpusLengthFilter() {
        Segmenter s = new Segmenter();
        speak(s, A, 0, 1_200);
        Segmenter.Segment seg = s.flushIdle(5_000).get(0);
        assertTrue(seg.durationMs() >= Utterance.MIN_USABLE_MS,
                "a 1.2s sentence must not be filtered out as a grunt");
    }
}
