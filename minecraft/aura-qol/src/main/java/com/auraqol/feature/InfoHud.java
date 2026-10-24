package com.auraqol.feature;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

import com.auraqol.AuraQoL;

/**
 * The Lunar-style info stack: FPS, coordinates, ping, clock and biome in one
 * small corner block.
 *
 * <p><b>Why not just use F3.</b> The debug screen answers a different
 * question. It is a developer's dump — twenty lines of chunk counts and
 * memory pressure — and it covers a third of the screen. What a player
 * actually wants standing in a mine is five facts, always in the same place,
 * small enough to ignore. That is a different feature, not a smaller F3, and
 * it is why every line here is independently switchable: the owner who plays
 * on one server wants ping, the one building a base wants coordinates, and
 * nobody wants both plus everything else.
 *
 * <p><b>Why the dark backing box.</b> White text on snow is invisible and
 * white text on sand is nearly so. Vanilla solves this with a drop shadow,
 * which is enough for one line of chat and not enough for a permanent block
 * of five. A ~50% black fill behind the text makes the readout independent
 * of the biome it is standing in — which matters especially because one of
 * the lines IS the biome.
 *
 * <h2>API that had to be read out of the jar</h2>
 * Minecraft 26.2 ships deobfuscated, so the following are the real names,
 * confirmed with {@code javap -p -s} against
 * {@code ~/.gradle/caches/fabric-loom/26.2/minecraft-client-only.jar}. Several
 * are NOT what a 1.20-era guess would produce, and each of these would have
 * been a silent compile failure or worse:
 *
 * <ul>
 *   <li><b>There is no {@code GuiGraphics}.</b> 26.2 split GUI drawing into a
 *       render-state extraction pass, and the object handed to a HUD element
 *       is {@code net.minecraft.client.gui.GuiGraphicsExtractor}. Its drawing
 *       methods are renamed to match: text is
 *       {@code text(Font, String, int, int, int)} (the 5-arg overload passes
 *       {@code true} for shadow), not {@code drawString}. {@code fill} and
 *       {@code pose()} survive unchanged.</li>
 *   <li><b>{@code ResourceLocation} is now {@code net.minecraft.resources.Identifier}</b>,
 *       built with {@code fromNamespaceAndPath}.</li>
 *   <li><b>{@code ResourceKey.location()} is now {@code identifier()}</b>, which
 *       is what turns a biome {@code Holder} into a printable name.</li>
 *   <li><b>Day time is gone; there are world clocks.</b> {@code Level} exposes
 *       {@code getDefaultClockTime()} (this dimension's own clock) and
 *       {@code getOverworldClockTime()}. There is no {@code getDayTime()}
 *       anywhere. Dimensions with no clock — the Nether and the End — return
 *       0, so the in-game time line is skipped there rather than printed as a
 *       permanent 6:00 AM lie.</li>
 *   <li>{@code Minecraft.getFps()}, {@code Minecraft.hasSingleplayerServer()},
 *       {@code Minecraft.getConnection()},
 *       {@code ClientPacketListener.getPlayerInfo(UUID)} and
 *       {@code PlayerInfo.getLatency()} are all as expected.</li>
 * </ul>
 *
 * <h2>Why a Fabric HUD element and not a mixin</h2>
 * fabric-api 0.157.0+26.2 ships neither {@code HudRenderCallback} nor
 * {@code HudLayerRegistrationCallback} — both are gone. The current API is
 * {@code net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry}
 * plus the {@link HudElement} functional interface, whose single method is
 * {@code extractRenderState(GuiGraphicsExtractor, DeltaTracker)}. Fabric
 * attaches these by wrapping the vanilla {@code Hud} draw calls, which buys
 * one thing for free and worth having: F1 and any other vanilla
 * hide-the-interface path already suppress this element, because it renders
 * inside the block those guards protect. A mixin would have had to
 * re-implement that.
 */
public final class InfoHud implements HudElement {

    /** Fabric wants a namespaced id per element; this one also names it in crash reports. */
    private static final Identifier ID = Identifier.fromNamespaceAndPath("auraqol", "info_hud");

    /** Wall-clock format. Locale.ROOT keeps AM/PM stable regardless of the machine's locale. */
    private static final DateTimeFormatter WALL_CLOCK =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ROOT);

    /** One line of the stack. Colour is per-line so ping can be graded without a second draw path. */
    private record Line(String text, int color) {
    }

    /**
     * A render throw would otherwise repeat sixty times a second and bury the
     * log under identical stack traces. One is enough to diagnose with.
     */
    private boolean loggedFailure;

    private InfoHud() {
    }

    /** Wire-up point for {@code Features}. Registers last so the stack draws over the rest of the HUD. */
    public static void register() {
        HudElementRegistry.addLast(ID, new InfoHud());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        try {
            draw(graphics);
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                AuraQoL.LOG.warn("[auraqol] info hud failed and is now silent for this session", t);
            }
        }
    }

    private void draw(GuiGraphicsExtractor graphics) {
        HudConfig cfg = HudConfig.get();
        if (!cfg.enabled) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }

        List<Line> lines = buildLines(mc, player, level, cfg);
        if (lines.isEmpty()) {
            return;
        }

        Font font = mc.font;
        int lineStep = font.lineHeight + 1;
        int textWidth = 0;
        for (Line line : lines) {
            textWidth = Math.max(textWidth, font.width(line.text()));
        }

        int boxW = textWidth + cfg.padding * 2;
        int boxH = lines.size() * lineStep - 1 + cfg.padding * 2;

        // The pose scales everything drawn after it, so the usable screen in
        // that space is the real screen divided by the scale. Working in those
        // coordinates is what keeps a bottom-right anchor correct at any scale.
        // Position comes from the shared layout the in-game editor drags, so
        // the corner enum in HudConfig is no longer consulted. It stays in the
        // file rather than being deleted: someone who set it by hand should
        // not have their config rejected, it simply no longer decides.
        float scale = HudLayout.scale(HudLayout.INFO);
        int screenW = Math.round(graphics.guiWidth() / scale);
        int screenH = Math.round(graphics.guiHeight() / scale);

        // Unscaled: the editor multiplies by the element's scale itself.
        HudLayout.reportSize(HudLayout.INFO, boxW, boxH);

        int x = Math.round(HudLayout.x(HudLayout.INFO, graphics.guiWidth()) / scale);
        int y = Math.round(HudLayout.y(HudLayout.INFO, graphics.guiHeight()) / scale);

        graphics.pose().pushMatrix();
        try {
            if (scale != 1.0f) {
                graphics.pose().scale(scale);
            }
            if (cfg.background) {
                graphics.fill(x, y, x + boxW, y + boxH, cfg.backgroundColor);
            }
            int textY = y + cfg.padding;
            for (Line line : lines) {
                graphics.text(font, line.text(), x + cfg.padding, textY, line.color());
                textY += lineStep;
            }
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private List<Line> buildLines(Minecraft mc, LocalPlayer player, ClientLevel level, HudConfig cfg) {
        List<Line> lines = new ArrayList<>(7);

        if (cfg.showFps) {
            lines.add(new Line(mc.getFps() + " FPS", cfg.textColor));
        }

        if (cfg.showCoords) {
            BlockPos pos = player.blockPosition();
            lines.add(new Line(
                    "XYZ " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ(),
                    cfg.textColor));
            if (cfg.showDirection) {
                lines.add(new Line(facing(player.getDirection()), cfg.labelColor));
            }
        }

        if (cfg.showPing) {
            // Singleplayer ping is a measure of how fast this PC talks to
            // itself, i.e. always 0 and never interesting. Skipped rather
            // than shown as zero, because a zero reads as "not connected".
            if (!mc.hasSingleplayerServer()) {
                Integer ping = latency(mc, player);
                if (ping != null) {
                    lines.add(new Line(ping + " ms", pingColor(ping, cfg)));
                }
            }
        }

        if (cfg.showTime) {
            String inGame = inGameClock(level);
            if (inGame != null) {
                lines.add(new Line(inGame, cfg.textColor));
            }
            lines.add(new Line(LocalTime.now().format(WALL_CLOCK), cfg.labelColor));
        }

        if (cfg.showBiome) {
            lines.add(new Line(biomeName(level, player.blockPosition()), cfg.textColor));
        }

        return lines;
    }

    /**
     * Latency for the local player, or null when there is nothing to ask.
     *
     * <p>The tab-list entry is the only client-side source of ping, and it
     * legitimately does not exist yet in the first moments after joining —
     * hence the null rather than a zero, so the line is absent instead of
     * briefly wrong.
     */
    private static Integer latency(Minecraft mc, LocalPlayer player) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return null;
        }
        PlayerInfo info = connection.getPlayerInfo(player.getUUID());
        if (info == null) {
            return null;
        }
        return info.getLatency();
    }

    /** Green / amber / red at the thresholds where a player actually starts feeling it. */
    private static int pingColor(int ms, HudConfig cfg) {
        if (ms <= 0) {
            return cfg.labelColor;
        }
        if (ms < 80) {
            return 0xFF55FF55;
        }
        if (ms < 200) {
            return 0xFFFFAA00;
        }
        return 0xFFFF5555;
    }

    /**
     * The dimension's own clock as a 12-hour time, or null if this dimension
     * has no clock at all.
     *
     * <p>26.2 replaced {@code getDayTime()} with per-dimension world clocks;
     * {@code getDefaultClockTime()} returns 0 flat in the Nether and the End
     * because they have none. Printing that as "6:00 AM" would be a readout
     * that is confidently wrong forever, so the line is dropped instead.
     *
     * <p>Tick 0 is 06:00 in Minecraft, and a day is 24000 ticks, so an hour
     * is 1000 ticks.
     */
    private static String inGameClock(ClientLevel level) {
        long ticks = level.getDefaultClockTime();
        if (ticks <= 0L) {
            return null;
        }
        long tod = Math.floorMod(ticks, 24000L);
        int hour24 = (int) ((tod / 1000L + 6L) % 24L);
        int minute = (int) ((tod % 1000L) * 60L / 1000L);
        int hour12 = hour24 % 12;
        if (hour12 == 0) {
            hour12 = 12;
        }
        String suffix = hour24 < 12 ? "AM" : "PM";
        return String.format(Locale.ROOT, "%d:%02d %s", hour12, minute, suffix);
    }

    /**
     * The biome at a position, printed the way a human writes it.
     *
     * <p>Deliberately the registry id and not the translated name: a datapack
     * biome usually has no translation key, and "biome.terralith.moonlight_valley"
     * is a worse answer than "Moonlight Valley". The namespace is dropped for
     * vanilla and kept otherwise, so a modded biome still says whose it is.
     */
    private static String biomeName(ClientLevel level, BlockPos pos) {
        return level.getBiome(pos)
                .unwrapKey()
                .map(key -> {
                    Identifier id = key.identifier();
                    String pretty = prettify(id.getPath());
                    return "minecraft".equals(id.getNamespace())
                            ? pretty
                            : pretty + " (" + id.getNamespace() + ")";
                })
                .orElse("Unknown Biome");
    }

    private static String prettify(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        boolean startOfWord = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_' || c == '/') {
                sb.append(' ');
                startOfWord = true;
            } else if (startOfWord) {
                sb.append(Character.toUpperCase(c));
                startOfWord = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * "North (-Z)" and friends.
     *
     * <p>The axis half is the point: knowing you face north is worth little
     * on its own, and knowing north is negative Z is what lets someone read
     * their own coordinates and walk the right way. Vertical facings cannot
     * come out of {@code Entity.getDirection()} — it is the horizontal facing
     * — but the switch is exhaustive anyway so a future enum constant is a
     * compile error rather than a blank line.
     */
    private static String facing(Direction dir) {
        return switch (dir) {
            case NORTH -> "North (-Z)";
            case SOUTH -> "South (+Z)";
            case WEST -> "West (-X)";
            case EAST -> "East (+X)";
            case UP -> "Up (+Y)";
            case DOWN -> "Down (-Y)";
        };
    }
}
