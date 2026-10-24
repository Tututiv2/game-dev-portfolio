package com.testpilot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TestPilot: lets an automated agent drive this client over a localhost HTTP
 * API, so nothing ever touches the OS keyboard, mouse, focus or screen.
 *
 * <p><b>Inert unless asked for.</b> The Fabric-26.1.2 instance this ships in is
 * also played by a human, so every behaviour here (the no-focus window, the
 * muted sound, the HTTP server, options never being saved) is gated on
 * {@link #ACTIVE}, which is true only when the game's environment has
 * {@code TESTPILOT=1} (or {@code TESTPILOT_PORT}), or {@code -Dtestpilot.enabled=true}.
 * {@code pilot.py launch} sets it; a normal launch does not.
 */
public final class TestPilot implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("testpilot");

    public static final boolean ACTIVE = computeActive();

    /** Allow the mouse to be grabbed if a human focuses the window. Default: never grab. */
    public static final boolean ALLOW_GRAB = flag("TESTPILOT_ALLOW_GRAB", "testpilot.allowGrab");

    /** Keep the game's sound. Default: master volume 0 (in memory only; options are never saved). */
    public static final boolean SOUND = flag("TESTPILOT_SOUND", "testpilot.sound");

    private static boolean optionsApplied;

    private static boolean computeActive() {
        String env = System.getenv("TESTPILOT");
        if (env != null && !env.isBlank() && !env.equals("0") && !env.equalsIgnoreCase("false")) {
            return true;
        }
        if (System.getenv("TESTPILOT_PORT") != null) {
            return true;
        }
        return Boolean.getBoolean("testpilot.enabled");
    }

    private static boolean flag(String env, String prop) {
        String e = System.getenv(env);
        if (e != null) {
            return e.equals("1") || e.equalsIgnoreCase("true");
        }
        return Boolean.getBoolean(prop);
    }

    public static String setting(String env, String prop, String dflt) {
        String v = System.getProperty(prop);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        v = System.getenv(env);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return dflt;
    }

    public static int port() {
        try {
            return Integer.parseInt(setting("TESTPILOT_PORT", "testpilot.port", "25620"));
        } catch (NumberFormatException e) {
            return 25620;
        }
    }

    @Override
    public void onInitializeClient() {
        if (!ACTIVE) {
            LOG.info("[testpilot] inactive (set TESTPILOT=1 to enable); doing nothing");
            return;
        }
        LOG.info("[testpilot] ACTIVE - port {}", port());
        PilotServer.start(port());
        ClientTickEvents.START_CLIENT_TICK.register(TestPilot::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(Jobs::endTick);
    }

    private static void startTick(Minecraft mc) {
        if (!optionsApplied && mc.options != null) {
            applyOptions(mc.options);
            optionsApplied = true;
        }
        Jobs.startTick(mc);
    }

    /**
     * In-memory only: {@code OptionsMixin} cancels every save while TestPilot
     * is active, so the owner's options.txt for this instance is never changed.
     */
    private static void applyOptions(Options o) {
        o.pauseOnLostFocus = false;
        o.rawMouseInput().set(false);
        // Background client on the owner's PC: keep it light.
        int fps = 30;
        try {
            fps = Integer.parseInt(setting("TESTPILOT_FPS", "testpilot.fps", "30"));
        } catch (NumberFormatException ignored) {
        }
        o.framerateLimit().set(fps);
        o.enableVsync().set(false);
        // Do not drop to 10 fps when "afk": screenshots should show a live frame.
        o.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        if (!SOUND) {
            o.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        }
        LOG.info("[testpilot] options applied in memory: pauseOnLostFocus=false rawMouse=false fps={} sound={}",
                fps, SOUND);
    }
}
