package com.secondwatch.game;

import com.secondwatch.SecondWatch;
import com.secondwatch.core.PhantomPlan;
import com.secondwatch.core.Segmenter;
import com.secondwatch.core.Utterance;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The microphone end of the whole design.
 *
 * <p>Everything either player says is decoded, cut into sentences and filed, and
 * that file is the ONLY thing the phantom is ever allowed to speak from. The
 * corollary is the sentence the game is built on: talking is how you stay
 * together, and talking is what arms the thing that separates you.
 *
 * <p>Three things here are load-bearing and easy to get wrong:
 *
 * <p><b>1. This runs on the voice thread, not the server thread.</b> Simple
 * Voice Chat fires microphone events on its own networking thread while the
 * server tick is separately reading the corpus to decide what the phantom says.
 * Every shared structure below is guarded, and the guard is not decoration.
 *
 * <p><b>2. Opus decoders are stateful and per speaker.</b> One shared decoder
 * fed two people's frames produces artefacts that sound like a bad connection -
 * which, in a mod whose entire premise is "is that really them", is the single
 * worst possible bug, because it makes the real voice sound fake.
 *
 * <p><b>3. Audio is capped.</b> 48 kHz mono 16-bit is ~96 KB per second, so an
 * unbounded corpus is a slow memory leak that ends a five-hour session with an
 * OOM. Oldest audio is evicted past {@link #MAX_STORED_MS}.
 */
public class VoiceBridge implements VoicechatPlugin {

    /**
     * How much recorded speech to keep. Roughly 23 MB at SVC's sample rate.
     *
     * <p>Also does the design a favour: the phantom's vocabulary is a rolling
     * window of the recent past, so it sounds like somebody who was here
     * earlier rather than somebody quoting the start of the session.
     */
    public static final long MAX_STORED_MS = 4 * 60 * 1000L;

    private static VoiceBridge instance;
    private static VoicechatServerApi serverApi;
    private static VoicechatApi api;

    private final Object lock = new Object();
    private final Segmenter segmenter = new Segmenter();
    private final Map<String, OpusDecoder> decoders = new HashMap<>();
    private final Map<String, List<Frame>> pending = new HashMap<>();
    private final Map<String, short[]> store = new LinkedHashMap<>();
    private long storedMs;
    private long nextRef = 1;

    private record Frame(long atMs, short[] pcm) {
    }

    public VoiceBridge() {
        instance = this;
    }

    @Override
    public String getPluginId() {
        return SecondWatch.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi voicechatApi) {
        api = voicechatApi;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class,
                e -> serverApi = e.getVoicechat());
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
        SecondWatch.LOG.info("voice bridge attached - everything said is now on the record");
    }

    // ------------------------------------------------------------- capture

    private void onMicrophone(MicrophonePacketEvent event) {
        RunState run = Runs.get();
        if (run == null || !run.started()) return;

        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) return;

        // Fires once per receiver as well as once at the server; only take the
        // server-side arrival or the same sentence lands in the corpus twice
        // and the phantom starts stammering.
        if (event.getReceiverConnection() != null) return;

        byte[] opus = event.getPacket().getOpusEncodedData();
        if (opus == null || opus.length == 0) return;

        Object rawPlayer = sender.getPlayer().getPlayer();
        if (!(rawPlayer instanceof ServerPlayer player)) return;
        String speaker = player.getName().getString();
        long now = run.runNowMs();

        synchronized (lock) {
            OpusDecoder decoder = decoders.computeIfAbsent(speaker, k -> api.createDecoder());
            short[] pcm;
            try {
                pcm = decoder.decode(opus);
            } catch (Exception e) {
                SecondWatch.LOG.warn("could not decode a frame from {}", speaker, e);
                return;
            }
            if (pcm == null || pcm.length == 0) return;

            pending.computeIfAbsent(speaker, k -> new ArrayList<>()).add(new Frame(now, pcm));
            segmenter.onFrame(speaker, now).ifPresent(seg -> commit(run, seg));
        }
    }

    /**
     * Close out anything that has gone quiet.
     *
     * <p>Called from the server tick. Without it, the last sentence somebody
     * says before falling silent has no following frame to close it, so it sits
     * open forever - and that is exactly the line the phantom most wants.
     */
    public static void tick() {
        VoiceBridge self = instance;
        RunState run = Runs.get();
        if (self == null || run == null || !run.started()) return;

        synchronized (self.lock) {
            for (Segmenter.Segment seg : self.segmenter.flushIdle(run.runNowMs())) {
                self.commit(run, seg);
            }
        }
    }

    /** Turn a closed segment into stored audio plus a corpus entry. */
    private void commit(RunState run, Segmenter.Segment seg) {
        List<Frame> frames = pending.get(seg.speakerId());
        if (frames == null || frames.isEmpty()) return;

        long endMs = seg.startMs() + seg.durationMs();
        List<Frame> mine = new ArrayList<>();
        List<Frame> rest = new ArrayList<>();
        for (Frame f : frames) {
            (f.atMs() < endMs ? mine : rest).add(f);
        }
        pending.put(seg.speakerId(), rest);
        if (mine.isEmpty()) return;

        int total = 0;
        for (Frame f : mine) total += f.pcm().length;
        short[] audio = new short[total];
        int at = 0;
        for (Frame f : mine) {
            System.arraycopy(f.pcm(), 0, audio, at, f.pcm().length);
            at += f.pcm().length;
        }

        String ref = "u" + (nextRef++);
        store.put(ref, audio);
        storedMs += seg.durationMs();
        evictIfNeeded();

        run.corpus().record(seg.speakerId(), seg.startMs(), seg.durationMs(), false, ref);
    }

    private void evictIfNeeded() {
        while (storedMs > MAX_STORED_MS && !store.isEmpty()) {
            var it = store.entrySet().iterator();
            var oldest = it.next();
            // 48 kHz mono: samples / 48 = milliseconds.
            storedMs -= oldest.getValue().length / 48L;
            it.remove();
        }
    }

    // ------------------------------------------------------------ playback

    /**
     * Say something in somebody else's voice, to exactly one person.
     *
     * <p>The three presentation tells are implemented here rather than faked,
     * which is what makes them fair: a player who works out that the voice never
     * gets quieter is right about the actual signal chain.
     */
    public static void speak(ServerPlayer victim, PhantomPlan plan, Utterance line,
                             double x, double y, double z) {
        speakLine(victim, line, x, y, z,
                plan.mono(), plan.fixedVolume(), plan.stripRoomTone());
    }

    /**
     * Say a recorded line to exactly one player, from a coordinate.
     *
     * <p>Split out from the phantom path so the bestiary can borrow it - a Mimic
     * is the same trick worn by something that is obviously not a person, which
     * is a different and colder scare than the phantom's.
     */
    public static void speakLine(ServerPlayer victim, Utterance line,
                                 double x, double y, double z,
                                 boolean mono, boolean fixedVolume, boolean stripRoomTone) {
        VoiceBridge self = instance;
        if (self == null || serverApi == null || api == null) return;

        short[] audio;
        synchronized (self.lock) {
            audio = self.store.get(line.audioRef());
        }
        if (audio == null) return;

        if (stripRoomTone) audio = gate(audio);

        VoicechatConnection connection = serverApi.getConnectionOf(victim.getUUID());
        if (connection == null) return;

        AudioChannel channel;
        if (mono) {
            // MONO: no position at all, so it arrives in both ears equally. A
            // real person is somewhere; this is nowhere.
            StaticAudioChannel stat = serverApi.createStaticAudioChannel(
                    UUID.randomUUID(), serverApi.fromServerLevel(victim.level()), connection);
            channel = stat;
        } else {
            LocationalAudioChannel loc = serverApi.createLocationalAudioChannel(
                    UUID.randomUUID(),
                    serverApi.fromServerLevel(victim.level()),
                    api.createPosition(x, y, z));
            // FIXED_VOLUME: an absurd falloff distance, so walking away from it
            // mid sentence does not make it any quieter.
            loc.setDistance(fixedVolume ? 4096f : 48f);
            channel = loc;
        }
        if (channel == null) return;

        // Belt and braces: the other player must never hear their own voice
        // coming out of the dark. That would give the whole thing away in one
        // moment and it would give it away to the wrong person.
        UUID onlyThem = victim.getUUID();
        channel.setFilter(p -> onlyThem.equals(p.getUuid()));
        channel.setCategory("secondwatch");

        AudioPlayer player = serverApi.createAudioPlayer(channel, api.createEncoder(), audio);
        player.startPlaying();
    }

    /**
     * Remove the quiet parts entirely, for the NO_BREATH tell.
     *
     * <p>Not a volume reduction - a hole. The giveaway is that the silence
     * between its words is absolute, in a way a live microphone in a real room
     * never is.
     */
    private static short[] gate(short[] pcm) {
        short[] out = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            out[i] = Math.abs(pcm[i]) < 900 ? 0 : pcm[i];
        }
        return out;
    }

    public static boolean ready() {
        return instance != null && serverApi != null;
    }
}
