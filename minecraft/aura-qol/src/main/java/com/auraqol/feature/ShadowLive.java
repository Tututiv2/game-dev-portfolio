package com.auraqol.feature;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.auraqol.AuraQoL;
import com.auraqol.shadow.Codec;
import com.auraqol.shadow.LiveState;
import com.auraqol.shadow.Msg;
import com.auraqol.shadow.ProfileCard;
import com.auraqol.shadow.RequestLimiter;
import com.auraqol.shadow.Wire;
import com.auraqol.skyblock.Fmt;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Shadow Live, client side: the pipe from the Shadow Prison server
 * ({@code shadow-live/PROTOCOL.md}). The server pushes its own numbers the
 * moment they happen; this class receives them, hands them to the pure
 * {@link LiveState}, and {@link ShadowHud} draws them.
 *
 * <p><b>Dormant unless the server says hello.</b> The mod sends HELLO only when
 * the server has announced {@code shadowprison:live_req} (so Hypixel, vanilla
 * and every other server never hear from it), and nothing is shown until a
 * WELCOME with {@code supported = true} arrives. On any address containing
 * "hypixel" it stays dormant whatever happens, and while it is active the
 * Hypixel features stand down ({@link Skyblock#inSkyblock} is false).
 *
 * <p><b>The mod only reads.</b> No click, no command, no chat line is ever sent
 * on the player's behalf. The only packets it sends are HELLO (once per
 * connection) and a profile REQUEST when the player asks for one, rate-limited
 * on this side as well as the server's.
 */
public final class ShadowLive {

    public static final Identifier LIVE = Identifier.fromNamespaceAndPath("shadowprison", "live");
    public static final Identifier LIVE_REQ = Identifier.fromNamespaceAndPath("shadowprison", "live_req");
    public static final long PROTOCOL = 1;
    /** Every feature this client can draw. */
    static final List<String> WANTS = List.of("breaks", "income", "balance", "progress", "boosts", "skill",
            "contract", "area", "lookup", "combat");

    /** How long a profile card stays up. */
    static final long CARD_MS = 30_000;
    /** A lookup with no answer after this long is reported and forgotten. */
    private static final long REQUEST_TIMEOUT_MS = 10_000;

    /** server -> client: the raw frame bytes, decoded by {@link Codec}. */
    record LivePayload(byte[] data) implements CustomPacketPayload {
        static final Type<LivePayload> TYPE = new Type<>(LIVE);
        static final StreamCodec<FriendlyByteBuf, LivePayload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBytes(p.data()),
                buf -> {
                    byte[] b = new byte[buf.readableBytes()];
                    buf.readBytes(b);
                    return new LivePayload(b);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** client -> server: HELLO and REQUEST frames. */
    record ReqPayload(byte[] data) implements CustomPacketPayload {
        static final Type<ReqPayload> TYPE = new Type<>(LIVE_REQ);
        static final StreamCodec<FriendlyByteBuf, ReqPayload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBytes(p.data()),
                buf -> {
                    byte[] b = new byte[buf.readableBytes()];
                    buf.readBytes(b);
                    return new ReqPayload(b);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record Pending(String query, long sentAt) {
    }

    // ---- everything below is touched on the client thread only
    private static LiveState state = newState();
    private static boolean connected;
    private static boolean helloSent;
    private static boolean toldToUpdate;
    private static long framesIn;
    private static long bytesIn;
    private static long decodeErrors;
    private static long unknownFrames;
    private static long connectedAt;
    private static boolean loggedDecodeError;
    private static boolean loggedUnknown;
    private static boolean loggedFailure;
    private static final RequestLimiter LIMITER = new RequestLimiter(0);
    private static final Map<Long, Pending> PENDING = new LinkedHashMap<>();
    private static long nextReqId = 1;
    private static ProfileCard.Card card;
    private static long cardUntil;
    private static KeyMapping profileKey;
    private static Vec3 lastPos;
    private static float lastYaw;
    private static float lastPitch;
    private static int tick;

    private ShadowLive() {
    }

    private static LiveState newState() {
        return new LiveState(HudConfig.get().moneyIdleSeconds * 1000L);
    }

    public static void register() {
        // Registering the payload types and a GLOBAL receiver at init is what
        // makes Fabric list shadowprison:live in its minecraft:register on every
        // join - Bukkit drops plugin messages on channels the client never
        // registered, so without this the server could not answer at all.
        PayloadTypeRegistry.clientboundPlay().register(LivePayload.TYPE, LivePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ReqPayload.TYPE, ReqPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(LivePayload.TYPE, (payload, ctx) -> safe(() -> onFrame(payload.data())));

        ClientPlayConnectionEvents.INIT.register((handler, mc) -> mc.execute(() -> safe(ShadowLive::forget)));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> mc.execute(() -> safe(() -> {
            connected = true;
            connectedAt = now();
            trySendHello();
        })));
        // The server's own minecraft:register may arrive after JOIN; this is the second chance.
        ServerboundPlayChannelEvents.REGISTER.register((handler, sender, mc, channels) -> {
            if (channels.contains(LIVE_REQ)) {
                mc.execute(() -> safe(ShadowLive::trySendHello));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> safe(ShadowLive::forget)));

        profileKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.auraqol.spprofile",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                KeyCategory.get()));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> safe(() -> onTick(mc)));
        ShadowHud.register();
    }

    // ------------------------------------------------------------ queries

    /** WELCOME arrived and was accepted on this connection: Shadow panels on, Hypixel features off. */
    public static boolean active() {
        return connected && state.active();
    }

    static LiveState state() {
        return state;
    }

    static ProfileCard.Card card() {
        return card != null && now() < cardUntil ? card : null;
    }

    static void closeCard() {
        card = null;
    }

    static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    // ------------------------------------------------------------ lifecycle

    /** Disconnect / new connection: both sides forget everything (PROTOCOL.md, handshake 4). */
    private static void forget() {
        state = newState();
        connected = false;
        helloSent = false;
        toldToUpdate = false;
        framesIn = 0;
        bytesIn = 0;
        decodeErrors = 0;
        unknownFrames = 0;
        PENDING.clear();
        card = null;
        LIMITER.configure(0);
        lastPos = null;
    }

    private static boolean onHypixel(Minecraft mc) {
        ServerData sd = mc.getCurrentServer();
        return sd != null && sd.ip != null && sd.ip.toLowerCase(Locale.ROOT).contains("hypixel");
    }

    /** HELLO exactly once per connection, as soon as the server has said it listens. */
    private static void trySendHello() {
        Minecraft mc = Minecraft.getInstance();
        if (helloSent || !connected || mc.getConnection() == null || onHypixel(mc)) {
            return;
        }
        if (!ClientPlayNetworking.canSend(ReqPayload.TYPE)) {
            return; // not a Shadow server (yet): stay silent
        }
        String version = FabricLoader.getInstance().getModContainer("auraqol")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        ClientPlayNetworking.send(new ReqPayload(Codec.encode(new Msg.Hello(PROTOCOL, version, WANTS))));
        helloSent = true;
        AuraQoL.LOG.info("[auraqol] Shadow Live: HELLO sent (protocol {}, {})", PROTOCOL, version);
    }

    // ------------------------------------------------------------ receive

    private static void onFrame(byte[] data) {
        Minecraft mc = Minecraft.getInstance();
        framesIn++;
        bytesIn += data.length;
        if (onHypixel(mc)) {
            return;
        }
        Msg m;
        try {
            m = Codec.decodeS2C(data);
        } catch (Wire.DecodeException e) {
            decodeErrors++;
            if (!loggedDecodeError) {
                loggedDecodeError = true;
                AuraQoL.LOG.debug("[auraqol] Shadow Live: dropped a frame that did not decode: {}", e.getMessage());
            }
            return;
        }
        long now = now();
        switch (m) {
            case Msg.Unknown u -> {
                unknownFrames++;
                if (!loggedUnknown) {
                    loggedUnknown = true;
                    AuraQoL.LOG.debug("[auraqol] Shadow Live: ignoring frame type {} v{}", u.type(), u.version());
                }
            }
            case Msg.Welcome w -> welcome(mc, w);
            case Msg.Response r -> response(mc, r, now);
            default -> state.apply(m, now);
        }
    }

    private static void welcome(Minecraft mc, Msg.Welcome w) {
        state.welcome(w, System.currentTimeMillis());
        AuraQoL.LOG.info("[auraqol] Shadow Live: WELCOME protocol {} supported={} features={} flush={}ms lookups={}/min",
                w.protocol(), w.supported(), w.features(), w.flushMs(), w.lookupsPerMinute());
        if (!w.supported()) {
            if (!toldToUpdate && mc.player != null) {
                toldToUpdate = true;
                mc.player.sendSystemMessage(Component.literal("[Aura] ").withColor(0xE0B01E)
                        .append(Component.literal("This server's live HUD needs a newer AuraQoL - update it to see the "
                                + "Shadow Prison panels.").withColor(0x8A8A8A)));
            }
            return;
        }
        LIMITER.configure(w.lookupsPerMinute());
    }

    private static void response(Minecraft mc, Msg.Response r, long now) {
        Pending p = PENDING.remove(r.reqId());
        if (p == null || mc.player == null) {
            return; // not ours, or it already timed out
        }
        switch ((int) r.status()) {
            case Msg.Response.OK -> {
                card = ProfileCard.of(r);
                cardUntil = now + CARD_MS;
                if (!HudConfig.get().spProfileEnabled) {
                    note(mc, "Got " + r.name() + "'s profile, but the card is hidden - /aq toggle sp-profile.");
                }
            }
            case Msg.Response.NOT_FOUND -> note(mc, "No player called " + p.query() + " was found.");
            case Msg.Response.RATE_LIMITED -> {
                LIMITER.rateLimited(now, r.retryAfterMs());
                note(mc, "Too many lookups - try again in " + Fmt.duration(Math.max(1000, r.retryAfterMs())) + ".");
            }
            case Msg.Response.UNSUPPORTED -> note(mc, "This server does not answer profile lookups.");
            default -> note(mc, "The server could not look up " + p.query() + " right now.");
        }
    }

    // ------------------------------------------------------------ requests

    /**
     * {@code /sp profile <name>} and the profile key. Returns a message for the
     * player if nothing was sent, or null if the request went out.
     */
    static String requestProfile(String query) {
        Minecraft mc = Minecraft.getInstance();
        String q = query == null ? "" : query.trim();
        if (!active()) {
            return "Not on a Shadow Prison server with the live HUD.";
        }
        if (!state.has("lookup")) {
            return "This server does not answer profile lookups.";
        }
        if (q.isEmpty() || q.length() > 64) {
            return "Give a player name or UUID.";
        }
        if (!ClientPlayNetworking.canSend(ReqPayload.TYPE) || mc.getConnection() == null) {
            return "The server is not listening for lookups.";
        }
        long now = now();
        long wait = LIMITER.tryAcquire(now);
        if (wait > 0) {
            return "Slow down - next lookup in " + Fmt.duration(Math.max(1000, wait)) + ".";
        }
        long id = nextReqId;
        nextReqId = nextReqId >= Integer.MAX_VALUE ? 1 : nextReqId + 1;
        PENDING.put(id, new Pending(q, now));
        ClientPlayNetworking.send(new ReqPayload(Codec.encode(new Msg.Request(id, Msg.Request.PROFILE, q))));
        return null;
    }

    /** Names in the tab list, for /sp profile suggestions. */
    static List<String> onlineNames() {
        Minecraft mc = Minecraft.getInstance();
        List<String> out = new ArrayList<>();
        if (mc.getConnection() == null) {
            return out;
        }
        for (PlayerInfo p : mc.getConnection().getOnlinePlayers()) {
            String n = p.getProfile().name();
            if (n != null && !n.isEmpty()) {
                out.add(n);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static void note(Minecraft mc, String text) {
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[Aura] ").withColor(0xE0B01E)
                    .append(Component.literal(text).withColor(0xC8C8C8)));
        }
    }

    // ------------------------------------------------------------ tick

    private static void onTick(Minecraft mc) {
        // The key is drained every tick so a press off-server does not fire later.
        while (profileKey != null && profileKey.consumeClick()) {
            lookedAtProfile(mc);
        }
        if (!active() || mc.player == null) {
            lastPos = null;
            return;
        }
        long now = now();
        Vec3 pos = mc.player.position();
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        if (lastPos != null && (pos.distanceToSqr(lastPos) > 0.0004
                || Math.abs(yaw - lastYaw) > 0.5f || Math.abs(pitch - lastPitch) > 0.5f)) {
            state.activity(now);
        }
        lastPos = pos;
        lastYaw = yaw;
        lastPitch = pitch;
        if (++tick % 20 == 0) {
            Iterator<Map.Entry<Long, Pending>> it = PENDING.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Pending> e = it.next();
                if (now - e.getValue().sentAt() > REQUEST_TIMEOUT_MS) {
                    it.remove();
                    note(mc, "No answer for " + e.getValue().query() + " - try again later.");
                }
            }
        }
        if (tick % 200 == 0) {
            state.prune(now);
        }
    }

    /**
     * The profile key. Vanilla's tab list is drawn while the mouse is captured
     * by the game - there is no cursor, so nothing in it can be "hovered" - so
     * the key looks up the player under your crosshair instead.
     */
    private static void lookedAtProfile(Minecraft mc) {
        if (mc.player == null || !active()) {
            return;
        }
        Entity e = mc.crosshairPickEntity;
        if (!(e instanceof Player p) || p == mc.player) {
            note(mc, "Look at a player first (or type /sp profile <name>).");
            return;
        }
        String why = requestProfile(p.getGameProfile().name());
        if (why != null) {
            note(mc, why);
        }
    }

    // ------------------------------------------------------------ status

    /** Lines for {@code /sp}. */
    static List<String> status() {
        List<String> out = new ArrayList<>();
        long now = now();
        if (!connected) {
            out.add("  Not connected to a server.");
            return out;
        }
        if (!helloSent) {
            out.add("  Dormant: this server does not offer Shadow Live.");
            return out;
        }
        if (!state.welcomed()) {
            out.add("  HELLO sent " + Fmt.duration(now - connectedAt) + " after joining, no WELCOME yet.");
            return out;
        }
        if (!state.supported()) {
            out.add("  The server speaks protocol " + state.protocol() + "; this AuraQoL speaks " + PROTOCOL
                    + ". Update AuraQoL.");
            return out;
        }
        out.add("  Live: protocol " + state.protocol() + ", flush " + state.flushMs() + " ms, lookups "
                + state.lookupsPerMinute() + "/min, clock offset " + state.clock().offsetMs() + " ms");
        out.add("  Streams: " + String.join(", ", state.features()));
        out.add("  Received " + framesIn + " frames, " + Fmt.compact(bytesIn) + " bytes"
                + (decodeErrors > 0 ? ", " + decodeErrors + " dropped (bad)" : "")
                + (unknownFrames > 0 ? ", " + unknownFrames + " ignored (newer types)" : ""));
        HudConfig c = HudConfig.get();
        boolean recent = c.spRecent;
        long win = c.spWindowMinutes * 60_000L;
        out.add("  Rate: " + modeLabel() + " of active play (AFK gaps cut out). Mining clock "
                + Fmt.duration(state.miningClock().activeMs(now)) + ", earnings clock "
                + Fmt.duration(state.earningClock().activeMs(now)) + ".");
        out.add("  Mining: " + Fmt.compact(state.blocksPerHour(now, recent, win)) + " blocks/h, est. value "
                + com.auraqol.shadow.ShadowFmt.money(state.valueCentsPerHour(now, recent, win)) + "/h; session "
                + state.sessionBlocks() + " blocks");
        out.add("  Earned: " + com.auraqol.shadow.ShadowFmt.money(state.incomeCentsPerHour(now, recent, win))
                + "/h; session " + com.auraqol.shadow.ShadowFmt.signedMoney(state.sessionIncomeCents()));
        return out;
    }

    static String modeLabel() {
        HudConfig c = HudConfig.get();
        return c.spRecent ? "last " + c.spWindowMinutes + "m" : "session avg";
    }

    /** {@code /sp reset}. */
    static void resetSession() {
        state.resetSession();
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                AuraQoL.LOG.warn("[auraqol] Shadow Live failed once (further errors silenced)", t);
            }
        }
    }
}
