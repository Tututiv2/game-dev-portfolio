package com.secondwatch.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the players have said, and the only place a phantom voice may come
 * from.
 *
 * <p>This class is the law made mechanical: <b>the phantom cannot say anything
 * that is not in here.</b> {@link #eligible} is the single gate, there is no
 * other accessor that returns audio, and the test suite asserts that a phantom
 * line always traces back to a real recorded utterance. If a future feature ever
 * wants the entity to say something new, it has to come through here and it has
 * to be visible in a diff.
 *
 * <p>The consequence players discover: talking is how you stay together, and
 * talking is what arms the thing that separates you. Every word is filed.
 */
public final class VoiceCorpus {

    /**
     * How long a line must sit before the phantom may use it.
     *
     * <p>This is the fuse on the passphrase. You and your friend agree a
     * challenge word; the moment you say it out loud it is recorded, and this
     * many milliseconds later it belongs to the thing as much as to you. Your
     * countermeasure is destroyed by the act of using it, and it takes exactly
     * this long to die.
     */
    public static final long ARMING_DELAY_MS = 90_000L;

    private final Map<String, List<Utterance>> bySpeaker = new LinkedHashMap<>();
    private final Set<Long> usedByPhantom = new HashSet<>();
    private long nextId = 1L;

    /** Record a line. Returns the stored utterance. */
    public Utterance record(String speakerId, long startMs, long durationMs, boolean wasReply, String audioRef) {
        Utterance u = new Utterance(nextId++, speakerId, startMs, durationMs, wasReply, audioRef);
        bySpeaker.computeIfAbsent(speakerId, k -> new ArrayList<>()).add(u);
        return u;
    }

    public List<Utterance> all(String speakerId) {
        return Collections.unmodifiableList(bySpeaker.getOrDefault(speakerId, List.of()));
    }

    public int size() {
        return bySpeaker.values().stream().mapToInt(List::size).sum();
    }

    /** Speakers the corpus has heard, in first-heard order. */
    public List<String> speakers() {
        return List.copyOf(bySpeaker.keySet());
    }

    /**
     * Every line the phantom is allowed to use to impersonate {@code speakerId}
     * right now, under this run's rules.
     *
     * <p>The filters, in order, and each one is a design claim:
     * <ol>
     *   <li>long enough to read as speech rather than a glitch</li>
     *   <li>old enough to have armed - this is the passphrase fuse</li>
     *   <li>if the run rolled STALE_ONLY, older still - so a line from this hour
     *       is a safe challenge and the journal will tell you so</li>
     *   <li>not already used, unless the run rolled REPEATS_ITSELF, in which
     *       case reuse is the whole giveaway and must be permitted</li>
     * </ol>
     */
    public List<Utterance> eligible(String speakerId, RunRules rules, long nowMs) {
        List<Utterance> out = new ArrayList<>();
        for (Utterance u : bySpeaker.getOrDefault(speakerId, List.of())) {
            if (!u.usableLength()) continue;

            long age = u.ageMsAt(nowMs);
            if (age < ARMING_DELAY_MS) continue;

            if (rules.tell() == Tell.STALE_ONLY && age < Tell.STALE_THRESHOLD_MS) continue;

            if (rules.tell() != Tell.REPEATS_ITSELF && usedByPhantom.contains(u.id())) continue;

            out.add(u);
        }
        return out;
    }

    /**
     * When this speaker last stopped talking, or -1 if they never have.
     *
     * <p>Drives {@link Tell#ONLY_REPLIES}: on such a run the phantom is
     * physically unable to speak into silence, so a pair who work that out can
     * starve it - and then discover what staying silent costs two people trying
     * to keep track of each other in the dark.
     */
    public long lastSpokeMs(String speakerId) {
        List<Utterance> list = bySpeaker.get(speakerId);
        if (list == null || list.isEmpty()) return -1L;
        return list.get(list.size() - 1).endMs();
    }

    /** Mark a line as spent by the phantom. */
    public void markUsed(Utterance u) {
        usedByPhantom.add(u.id());
    }

    public boolean wasUsedByPhantom(Utterance u) {
        return usedByPhantom.contains(u.id());
    }

    /**
     * True if this exact utterance came out of this corpus. Used by the test
     * suite to prove the phantom never invents - see
     * {@code PhantomDirectorTest#phantomNeverSaysAnythingNew}.
     */
    public boolean contains(Utterance u) {
        List<Utterance> list = bySpeaker.get(u.speakerId());
        return list != null && list.contains(u);
    }
}
