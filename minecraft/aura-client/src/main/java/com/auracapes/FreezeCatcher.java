package com.auracapes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Says WHICH mod froze the game, instead of guessing from log timing.
 *
 * <p>The game thread bumps a heartbeat every client tick (20 a second, and ticks stop the moment
 * the game thread is stuck). A daemon thread checks it four times a second; once the game has been
 * silent for {@link #STALL_MS}, it samples the game thread's stack every 250 ms until the game comes
 * back, then writes one entry to {@code config/aura-freezes.log}: when, how long, and the frames it
 * was stuck in, most frequent first. The first frame outside Java/Minecraft/Fabric is named as the
 * suspect, which is the line that answers "which mod".
 *
 * <p>Costs nothing while the game runs: one volatile write per tick, one read every 250 ms.
 * Sampling only happens during a freeze, which is exactly when nobody can feel it.
 */
public final class FreezeCatcher {

    private static final long STALL_MS = 2000;
    private static final long CHECK_MS = 250;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static volatile long lastTick = System.currentTimeMillis();
    private static volatile Thread gameThread;

    private FreezeCatcher() {
    }

    public static void start() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (gameThread == null) {
                gameThread = Thread.currentThread();
            }
            lastTick = System.currentTimeMillis();
        });
        Thread t = new Thread(FreezeCatcher::watch, "Aura freeze catcher");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    private static void watch() {
        while (true) {
            try {
                Thread.sleep(CHECK_MS);
                Thread game = gameThread;
                long silent = System.currentTimeMillis() - lastTick;
                // Minimised/unfocused: Dynamic FPS may slow the loop to a crawl on purpose. Not a freeze.
                if (game != null && silent >= STALL_MS && net.minecraft.client.Minecraft.getInstance().isWindowActive()) {
                    record(game, lastTick);
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                AuraCapes.LOG.warn("[auracapes] freeze catcher hiccup", e);
            }
        }
    }

    /** Samples until the heartbeat moves again, then writes the report. */
    private static void record(Thread game, long stalledSince) throws InterruptedException {
        Map<String, Integer> frames = new LinkedHashMap<>();
        Map<String, Integer> suspects = new LinkedHashMap<>();
        int samples = 0;
        String firstTop = null;
        while (lastTick == stalledSince) {
            StackTraceElement[] st = game.getStackTrace();
            samples++;
            if (st.length > 0 && firstTop == null) {
                firstTop = st[0].toString();
            }
            for (int i = 0; i < Math.min(st.length, 40); i++) {
                frames.merge(st[i].toString(), 1, Integer::sum);
            }
            String s = suspect(st);
            if (s != null) {
                suspects.merge(s, 1, Integer::sum);
            }
            Thread.sleep(CHECK_MS);
        }
        long ms = System.currentTimeMillis() - stalledSince;
        StringBuilder out = new StringBuilder();
        out.append("=== ").append(LocalDateTime.now().format(TIME)).append("  game froze for ")
                .append(String.format("%.1f", ms / 1000.0)).append(" s (").append(samples).append(" samples)\n");
        out.append("suspect: ").append(suspects.isEmpty() ? "none outside Minecraft/Java (graphics driver, "
                + "disk or Windows?)" : top(suspects, 3)).append('\n');
        out.append("most common frames:\n");
        final int n = samples;
        frames.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(25)
                .forEach(e -> out.append(String.format("  %3d/%d  %s%n", e.getValue(), n, e.getKey())));
        out.append('\n');
        try {
            Path log = FabricLoader.getInstance().getConfigDir().resolve("aura-freezes.log");
            Files.writeString(log, out.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            AuraCapes.LOG.warn("[auracapes] could not write aura-freezes.log", e);
        }
        AuraCapes.LOG.warn("[auracapes] game froze for {} ms; suspect: {} (details in config/aura-freezes.log)",
                ms, suspects.isEmpty() ? "none" : top(suspects, 1));
    }

    /** Mixin-merged handlers carry their mod id: handler$zza000$skyblocker$onRender, wrapMethod$zzo000$dynamic_fps... */
    private static final java.util.regex.Pattern MIXIN = java.util.regex.Pattern.compile("\\$[a-z]{3}\\d{3}\\$([a-z0-9_]+)\\$");

    /** The first frame that belongs to a mod: not the JDK, not Minecraft, not the loader or mixin glue. */
    private static String suspect(StackTraceElement[] st) {
        for (StackTraceElement e : st) {
            java.util.regex.Matcher m = MIXIN.matcher(e.getMethodName());
            if (m.find()) {
                return "mod " + m.group(1) + " (mixin in " + e.getClassName() + ")";
            }
            String c = e.getClassName();
            if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.") || c.startsWith("net.minecraft.")
                    || c.startsWith("com.mojang.") || c.startsWith("org.lwjgl.") || c.startsWith("net.fabricmc.")
                    || c.startsWith("org.spongepowered.") || c.startsWith("com.llamalad7.") || c.startsWith("it.unimi.")
                    || c.startsWith("com.google.") || c.startsWith("io.netty.") || c.startsWith("org.slf4j.")
                    || c.startsWith("org.apache.") || c.startsWith("kotlin.") || c.startsWith("kotlinx.")) {
                continue;
            }
            int cut = c.indexOf('.', c.indexOf('.', c.indexOf('.') + 1) + 1);
            return cut > 0 ? c.substring(0, cut) : c;
        }
        return null;
    }

    private static String top(Map<String, Integer> m, int n) {
        StringBuilder b = new StringBuilder();
        m.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).limit(n)
                .forEach(e -> b.append(b.length() == 0 ? "" : ", ").append(e.getKey()).append(" (").append(e.getValue()).append(')'));
        return b.toString();
    }
}
