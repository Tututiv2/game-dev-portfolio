package com.secondwatch.game;

import com.secondwatch.core.PhantomPlan;
import com.secondwatch.core.Utterance;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/**
 * The only place in the mod allowed to mention Simple Voice Chat.
 *
 * <p>Simple Voice Chat is {@code compileOnly} and genuinely optional - the
 * scenario still runs without it, just quieter and much less frightening. But
 * "optional" is a lie unless something enforces it: touching {@link VoiceBridge}
 * from anywhere else would load the class, and loading a class whose fields
 * reference an absent library throws {@code NoClassDefFoundError} at whatever
 * unlucky moment first reaches that code path. That is a crash on night three,
 * not a clean startup error.
 *
 * <p>So every call goes through here, behind a flag resolved once at load.
 */
public final class Voice {

    private static final String VOICECHAT_MOD_ID = "voicechat";

    private static final boolean PRESENT =
            FabricLoader.getInstance().isModLoaded(VOICECHAT_MOD_ID);

    private Voice() {
    }

    public static boolean present() {
        return PRESENT;
    }

    /** Close out any sentence that has gone quiet. Call from the server tick. */
    public static void tick() {
        if (!PRESENT) return;
        VoiceBridge.tick();
    }

    /** Speak a recorded line, in the borrowed voice, to exactly one player. */
    public static void speak(ServerPlayer victim, PhantomPlan plan, Utterance line,
                             double x, double y, double z) {
        if (!PRESENT) return;
        VoiceBridge.speak(victim, plan, line, x, y, z);
    }

    /** Speak a recorded line to one player from a coordinate. Used by the bestiary. */
    public static void speakLine(ServerPlayer victim, Utterance line,
                                 double x, double y, double z,
                                 boolean mono, boolean fixedVolume, boolean stripRoomTone) {
        if (!PRESENT) return;
        VoiceBridge.speakLine(victim, line, x, y, z, mono, fixedVolume, stripRoomTone);
    }

    public static boolean ready() {
        return PRESENT && VoiceBridge.ready();
    }
}
