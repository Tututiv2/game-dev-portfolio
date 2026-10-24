package com.thestill.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * <b>The</b> per-character gradient. Ported from Shadow Prison's {@code NickDisplay}.
 *
 * <p>Do not re-implement this locally. A 2026-07-28 audit of that codebase found
 * <em>eight</em> near-copies, seven of which had dropped the italic kill and so
 * rendered italic anywhere they were used in an item name or lore. One canonical
 * implementation is the whole point.
 *
 * <p>This is also the seed of the colour engine the build plan schedules for
 * Phase 3 — perimeter lamps, then nametags, chat themes, bossbars, the Wake, and
 * every cosmetic after. Phase 1 uses only flat colours, but everything already
 * goes through this path, so lighting it up later is a change here rather than
 * everywhere.
 */
public final class Gradients {

    private Gradients() {
    }

    /**
     * Bold text fading from {@code start} to {@code end}, one step per character.
     *
     * <p>{@code ITALIC=false} is set on the <b>parent</b>, once, rather than per
     * character. Adventure children inherit any decoration they do not set
     * themselves, and Minecraft defaults item display names and lore to italic —
     * so a gradient built without this looks correct in chat and wrong on an item,
     * which is exactly the sort of bug that survives review.
     */
    public static Component bold(String text, TextColor start, TextColor end) {
        return build(text, start, end, true);
    }

    public static Component plain(String text, TextColor start, TextColor end) {
        return build(text, start, end, false);
    }

    private static Component build(String text, TextColor start, TextColor end, boolean bold) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        Component result = Component.empty();
        int length = text.length();
        for (int i = 0; i < length; i++) {
            float t = length == 1 ? 0.5f : (float) i / (length - 1);
            Component character = Component.text(String.valueOf(text.charAt(i)),
                    blend(start, end, t));
            result = result.append(bold
                    ? character.decoration(TextDecoration.BOLD, true)
                    : character);
        }
        return result.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * A gradient that <em>moves</em> — one band of colour sweeping along the text.
     *
     * <p>The difference from {@link #bold} is the phase. A static gradient maps
     * character index straight onto {@code t}; this offsets it by a phase that a
     * caller advances every few ticks, so the bright end travels.
     *
     * <p>It is a <b>triangle wave, not a sawtooth</b>, and that is the whole reason it
     * looks like light rather than like a loop: a sawtooth snaps from {@code end} back
     * to {@code start} at the wrap, which reads as a seam crawling through the words.
     * Going up and back down means there is no wrap to see.
     *
     * @param cycles how many bright bands are on the text at once; 1 is a single sweep
     * @param phase  advanced by the caller, any float — only its fraction matters
     */
    public static Component sweep(String text, TextColor start, TextColor end,
                                  float cycles, float phase, boolean bold) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        Component result = Component.empty();
        int length = text.length();
        for (int i = 0; i < length; i++) {
            float raw = (i / (float) Math.max(1, length)) * cycles + phase;
            float wrapped = raw - (float) Math.floor(raw);
            float triangle = wrapped < 0.5f ? wrapped * 2f : (1f - wrapped) * 2f;
            Component character = Component.text(String.valueOf(text.charAt(i)),
                    blend(start, end, triangle));
            result = result.append(bold
                    ? character.decoration(TextDecoration.BOLD, true)
                    : character);
        }
        return result.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * RGB chrome — polished metal catching coloured light.
     *
     * <p>Not a two-stop gradient, which is why it needs its own method. Chrome reads as
     * chrome because of two things happening at once: a <b>sharp specular band</b>
     * travelling along the surface, and a <b>slow hue rotation</b> in the reflection.
     * A silver-to-white sweep gives you the first and looks like grey text; a rainbow
     * gives you the second and looks like a rainbow. Together they look like metal.
     *
     * <p>The band is {@code pow(triangle, 6)} — a high power is what makes it a
     * glint rather than a smear, and it is the difference between "shiny" and "washed
     * out". Underneath, saturation stays low (metal is not coloured, it is
     * <em>reflecting</em> colour) and the hue drifts about a fifth as fast as the band
     * travels, so the sheen and the tint are visibly two separate motions.
     *
     * <p>Reserved for the top rank. It is the only chrome on the server and that is the
     * product — the moment a second thing has it, neither is special.
     */
    public static Component chrome(String text, float phase, boolean bold) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        Component result = Component.empty();
        int length = text.length();
        for (int i = 0; i < length; i++) {
            float along = i / (float) Math.max(1, length);
            // The travelling specular band.
            float raw = along * 1.4f + phase;
            float wrapped = raw - (float) Math.floor(raw);
            float triangle = wrapped < 0.5f ? wrapped * 2f : (1f - wrapped) * 2f;
            float glint = (float) Math.pow(triangle, 6);

            // The reflection's own slow drift, a fifth of the band's speed.
            float hue = (along * 0.45f + phase * 0.2f) % 1f;
            // Base steel, lifted toward white by the glint. Saturation stays low so it
            // reads as metal reflecting colour rather than as coloured text.
            float saturation = 0.32f * (1f - glint * 0.7f);
            float brightness = 0.55f + 0.45f * glint;
            int rgb = java.awt.Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;

            Component character = Component.text(String.valueOf(text.charAt(i)),
                    TextColor.color(rgb));
            result = result.append(bold
                    ? character.decoration(TextDecoration.BOLD, true)
                    : character);
        }
        return result.decoration(TextDecoration.ITALIC, false);
    }

    /** Linear interpolation, per channel. */
    public static TextColor blend(TextColor start, TextColor end, float t) {
        int startRed = (start.value() >> 16) & 0xFF;
        int startGreen = (start.value() >> 8) & 0xFF;
        int startBlue = start.value() & 0xFF;
        int endRed = (end.value() >> 16) & 0xFF;
        int endGreen = (end.value() >> 8) & 0xFF;
        int endBlue = end.value() & 0xFF;
        return TextColor.color(
                (int) (startRed + (endRed - startRed) * t),
                (int) (startGreen + (endGreen - startGreen) * t),
                (int) (startBlue + (endBlue - startBlue) * t));
    }

    public static TextColor parse(String hex, TextColor fallback) {
        if (hex == null || hex.isBlank()) {
            return fallback;
        }
        TextColor parsed = TextColor.fromHexString(hex.startsWith("#") ? hex : "#" + hex);
        return parsed != null ? parsed : fallback;
    }

    /**
     * Snaps an arbitrary colour to the nearest of the sixteen named ones.
     *
     * <p>Needed because a scoreboard {@code Team}'s colour is a
     * {@link NamedTextColor} and nothing else — a protocol limit, not a Paper
     * gap. Above-head names therefore cannot be a true gradient, which is why
     * Phase 6's Wake nametag will need a {@code TextDisplay} riding the player
     * instead.
     */
    public static NamedTextColor nearestNamed(TextColor colour) {
        NamedTextColor best = NamedTextColor.WHITE;
        int bestDistance = Integer.MAX_VALUE;
        int red = (colour.value() >> 16) & 0xFF;
        int green = (colour.value() >> 8) & 0xFF;
        int blue = colour.value() & 0xFF;
        for (NamedTextColor candidate : NamedTextColor.NAMES.values()) {
            int dr = red - ((candidate.value() >> 16) & 0xFF);
            int dg = green - ((candidate.value() >> 8) & 0xFF);
            int db = blue - (candidate.value() & 0xFF);
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
