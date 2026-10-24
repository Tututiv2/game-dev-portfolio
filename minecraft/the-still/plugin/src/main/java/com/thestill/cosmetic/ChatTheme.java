package com.thestill.cosmetic;

import com.thestill.ui.Gradients;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;

/**
 * One animated look, for a name or a line of chat.
 *
 * <h2>Three stops, not two</h2>
 * Shadow Prison's palettes are <b>three</b> colours cycled with a wrap back to the
 * first, and that is not decoration — it is why its themes read as materials rather
 * than as gradients. Two stops can only ever say "this fades from A to B". Three can
 * say "dark, hot, white-hot", which is what makes Solar Flare read as heat instead of
 * as orange.
 *
 * <h2>The shader index is carried even though nothing reads it yet</h2>
 * Over there a theme's real payload is a {@code shaderIndex} that a resource-pack text
 * shader decodes from per-character RGB markers. That index is <b>a wire protocol</b>:
 * its own source says reordering the enum silently repoints every saved player choice.
 * It is stored here so that the day this server ships a pack, the client path can be
 * switched on without re-deriving a single number — and so the two libraries stay
 * comparable in the meantime.
 *
 * <p>Until then {@link #render} animates the palette <em>server-side</em>, which needs
 * nothing installed and costs a few packets. Motion and effect themes therefore read as
 * their colours in movement rather than as their geometry.
 *
 * @param id      stable, lowercase, stored on the profile — never change one
 * @param name    what it says in the menu
 * @param source  which library it belongs to, and which of the two chooser buttons
 * @param motion  COLOUR themes recolour; MOTION themes read as movement
 * @param icon    the tile in the picker
 * @param a       first palette stop, or null when {@code rainbow}
 * @param b       second stop
 * @param c       third stop
 * @param rainbow true for a full hue cycle with no fixed palette
 * @param index   the source's shader index. A gap in the numbering is deliberate
 * @param unlock  the perk that opens it, or null for everybody
 */
public record ChatTheme(String id, String name, Source source, Motion motion, Material icon,
                        Integer a, Integer b, Integer c, boolean rainbow, int index,
                        String unlock) {

    /** Which library a theme came from. Drives the two-button chooser. */
    public enum Source { SHADOW, STILL }

    /** Whether a theme reads as a colour choice or as movement. */
    public enum Motion { COLOUR, MOTION }

    public ChatTheme {
        if (!id.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("theme id '" + id + "' must be lowercase "
                    + "[a-z0-9_.-] — it is stored on profiles and typed in commands");
        }
    }

    public boolean isMotion() {
        return motion == Motion.MOTION;
    }

    /**
     * Renders text in this theme at a given phase.
     *
     * <p>A rainbow theme cycles hue; everything else walks the three stops in a loop
     * that returns to the first, so there is no seam where the palette snaps back.
     * A COLOUR theme ignores the phase and sits still.
     */
    public Component render(String text, float phase, boolean bold) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        float offset = isMotion() ? phase : 0f;
        Component out = Component.empty();
        int length = text.length();
        for (int i = 0; i < length; i++) {
            float along = length == 1 ? 0.5f : i / (float) length;
            float t = along + offset;
            t -= (float) Math.floor(t);
            Component ch = Component.text(String.valueOf(text.charAt(i)), colourAt(t));
            out = out.append(bold ? ch.decoration(TextDecoration.BOLD, true) : ch);
        }
        return out.decoration(TextDecoration.ITALIC, false);
    }

    /** The palette at a point in the loop. Three stops, wrapping back to the first. */
    public TextColor colourAt(float t) {
        if (rainbow || a == null) {
            // Saturation held below 1 so it lifts toward white rather than screaming,
            // which is what the source's own rainbow does.
            return TextColor.color(java.awt.Color.HSBtoRGB(t, 0.88f, 1f) & 0xFFFFFF);
        }
        TextColor first = TextColor.color(a);
        TextColor second = TextColor.color(b);
        TextColor third = TextColor.color(c);
        float scaled = t * 3f;
        int segment = (int) scaled;
        float within = scaled - segment;
        return switch (segment) {
            case 0 -> Gradients.blend(first, second, within);
            case 1 -> Gradients.blend(second, third, within);
            default -> Gradients.blend(third, first, within);
        };
    }

    /** A moving preview for the tile, so the picker shows what the theme does. */
    public Component preview(float phase) {
        return render(name, phase, true);
    }

    public boolean unlockedBy(java.util.function.Predicate<String> hasPerk) {
        return unlock == null || hasPerk.test(unlock);
    }
}
