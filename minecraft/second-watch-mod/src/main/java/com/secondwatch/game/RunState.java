package com.secondwatch.game;

import com.secondwatch.SecondWatch;
import com.secondwatch.core.Dread;
import com.secondwatch.core.Journal;
import com.secondwatch.core.PhantomDirector;
import com.secondwatch.core.PreviousGroup;
import com.secondwatch.core.Rng;
import com.secondwatch.core.RunRules;
import com.secondwatch.core.TheSeat;
import com.secondwatch.core.VoiceCorpus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * One world's run: the rolled rules, who was here before, their journal, the
 * voice corpus and the escalation clock.
 *
 * <p><b>Persistence is nearly free and that is not an accident.</b> Because the
 * core is deterministic, the rules, the previous group and every word of their
 * journal are all reproducible from the world seed alone - so none of it is
 * saved. The only things that genuinely accumulate are which night it is and how
 * much dread has been fed, and those are two numbers in a properties file. A
 * corrupted or deleted save costs the players their progress and not their
 * story.
 *
 * <p>The voice corpus is deliberately NOT persisted. Recordings of the players'
 * speech live in memory for the session and are gone when the server stops.
 */
public final class RunState {

    /**
     * How long a chapter lasts once the sun has stopped coming up.
     *
     * <p>Nights cannot be measured by the sky any more - that is the whole
     * premise - so a "night" becomes a chapter of real time. Tunable, because
     * this is the single number that decides whether a recorded session is a
     * tight 45 minutes or a slow burn.
     */
    public static final long NIGHT_MS = 20 * 60 * 1000L;

    private final long seed;
    private final RunRules rules;
    private final PreviousGroup group;
    private final Dread dread;
    private final VoiceCorpus corpus = new VoiceCorpus();
    private final PhantomDirector director;
    private final TheSeat seat = new TheSeat();
    private boolean ended;

    /** Wall-clock ms the run began. Everything the corpus stores is relative to this. */
    private long runStartedAtMs = -1;

    /** Wall-clock ms this night started, or -1 before the run begins. */
    private long nightStartedAtMs = -1;
    private boolean started;

    private RunState(long seed) {
        this.seed = seed;
        this.rules = RunRules.roll(seed);
        this.group = PreviousGroup.generate(rules, new Rng(seed));
        this.dread = new Dread(rules);
        this.director = new PhantomDirector(rules, corpus, new Rng(seed).fork("phantom"));
    }

    public static RunState forSeed(long seed) {
        return new RunState(seed);
    }

    public long seed() {
        return seed;
    }

    public RunRules rules() {
        return rules;
    }

    public PreviousGroup group() {
        return group;
    }

    public Dread dread() {
        return dread;
    }

    public VoiceCorpus corpus() {
        return corpus;
    }

    public PhantomDirector director() {
        return director;
    }

    /** The way out. It fits one. */
    public TheSeat seat() {
        return seat;
    }

    public boolean ended() {
        return ended;
    }

    public void markEnded() {
        ended = true;
    }

    public boolean started() {
        return started;
    }

    /**
     * Run-relative time. The voice corpus is stored on this clock rather than
     * wall time so that an utterance's age means "how long ago in this run",
     * which is what every rule about it is actually phrased in terms of.
     */
    public long runNowMs() {
        if (runStartedAtMs < 0) return 0L;
        return System.currentTimeMillis() - runStartedAtMs;
    }

    public int night() {
        return dread.night();
    }

    /**
     * The journal, built fresh against whoever is currently playing.
     *
     * <p>Regenerated rather than stored because the prescient entry has to name
     * a player who is actually here, and the mod has no way of knowing who that
     * is until somebody joins.
     */
    public Journal journalFor(List<String> playerNames) {
        return Journal.generate(rules, group, playerNames, new Rng(seed));
    }

    public static List<String> namesOf(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            names.add(p.getName().getString());
        }
        return names;
    }

    public void begin(long nowMs) {
        started = true;
        nightStartedAtMs = nowMs;
        runStartedAtMs = nowMs;
    }

    /**
     * Advance the chapter clock.
     *
     * @return true if a new night just began
     */
    public boolean tickChapter(long nowMs) {
        if (!started || nightStartedAtMs < 0) return false;
        if (nowMs - nightStartedAtMs < NIGHT_MS) return false;
        nightStartedAtMs = nowMs;
        dread.advanceNight();
        return true;
    }

    /** Force the next chapter. For testing, and for a recording that needs to move. */
    public void forceNextNight(long nowMs) {
        nightStartedAtMs = nowMs;
        dread.advanceNight();
    }

    /** True once dawn should have arrived and did not. */
    public boolean sunIsGone() {
        return started && night() >= 2;
    }

    // ------------------------------------------------------------ persistence

    private static Path fileIn(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("secondwatch.properties");
    }

    public void save(MinecraftServer server) {
        Properties p = new Properties();
        p.setProperty("seed", Long.toString(seed));
        p.setProperty("started", Boolean.toString(started));
        p.setProperty("night", Integer.toString(dread.night()));
        p.setProperty("dread", Double.toString(dread.value()));
        p.setProperty("ended", Boolean.toString(ended));
        seat.departed().ifPresent(who -> p.setProperty("departed", who));
        try (OutputStream out = Files.newOutputStream(fileIn(server))) {
            p.store(out, "Second Watch - only what genuinely accumulates. "
                    + "Everything else is reproducible from the seed.");
        } catch (IOException e) {
            SecondWatch.LOG.error("could not save run state", e);
        }
    }

    public static RunState load(MinecraftServer server, long seed) {
        RunState state = new RunState(seed);
        Path file = fileIn(server);
        if (!Files.exists(file)) return state;

        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            SecondWatch.LOG.error("could not read run state, starting fresh", e);
            return state;
        }

        long savedSeed = Long.parseLong(p.getProperty("seed", "0"));
        if (savedSeed != seed) {
            SecondWatch.LOG.warn("run state belongs to seed {} but this world is {} - ignoring it",
                    savedSeed, seed);
            return state;
        }

        state.started = Boolean.parseBoolean(p.getProperty("started", "false"));
        state.ended = Boolean.parseBoolean(p.getProperty("ended", "false"));
        if (state.started) state.runStartedAtMs = System.currentTimeMillis();
        int night = Integer.parseInt(p.getProperty("night", "1"));
        for (int i = 1; i < night; i++) state.dread.advanceNight();

        double saved = Double.parseDouble(p.getProperty("dread", "0"));
        // feed() rather than assignment, so the "never decreases" rule holds
        // across a restart too: the night floor may already exceed what was
        // saved, and in that case the floor wins.
        double gap = saved - state.dread.value();
        if (gap > 0) state.dread.feed(gap);

        return state;
    }
}
