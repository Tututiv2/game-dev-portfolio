package com.thestill.cosmetic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * The wire protocol between a {@link ChatTheme} and the resource pack's text shader.
 *
 * <p>Ported byte-for-byte from Shadow Prison's {@code ui/AnimatedRainbowText}. The pack at
 * {@code resource-pack/assets/minecraft/shaders/core/rendertype_text.vsh} was copied
 * verbatim from that server, so its decoder is <b>already deployed</b> and cannot be
 * negotiated with — every number below is fixed by a GLSL file, not chosen here.
 *
 * <h2>What a marker is</h2>
 * Each glyph is sent as its own component in a colour nobody would ever pick by hand:
 *
 * <pre>
 *   red   = the glyph's phase along the line, 0..255. The fragment shader adds GameTime
 *           to it, so ONE packet animates forever.
 *   green
 *   blue  = the marker slot, where slot = shaderIndex * 2 + direction.
 * </pre>
 *
 * The slot rides green and blue in five families, because a single family only has 64
 * usable slots and there are 141 themes:
 *
 * <table>
 *   <tr><th>Family</th><th>Slots</th><th>Marker</th><th>Its quartered shadow</th></tr>
 *   <tr><td>1</td><td>0–63</td><td>{@code #RR(01+4S)(FE-4S)}</td><td>{@code (S, 63-S)}</td></tr>
 *   <tr><td>2</td><td>64–126</td><td>{@code #RR(03+4s)(FA-4s)}</td><td>{@code (s, 62-s)}</td></tr>
 *   <tr><td>3</td><td>127–188</td><td>{@code #RR(02+4t)(F6-4t)}</td><td>{@code (t, 61-t)}</td></tr>
 *   <tr><td>4</td><td>189–248</td><td>{@code #RR(04+4u)(EE-4u)}</td><td>{@code (1+u, 59-u)}</td></tr>
 *   <tr><td>5</td><td>249–311</td><td>{@code #RRFF(06+4j)}</td><td>{@code (63, 1+j)}</td></tr>
 * </table>
 *
 * <p>The bases are not spacing, they are <em>proofs</em>. Families 1–4 take one
 * {@code green mod 4} residue each (1, 3, 2, 0) so no marker can be read as two families
 * at once, and each family stops one slot before its blue byte would go negative — which
 * is why family 2 ends at 126 and not 127, and why the next base is an odd 127. Family 4
 * starts its green at 4 rather than 0 <b>specifically so a marker is never {@code #RR00xx}</b>,
 * which is a plain blue a player really could pick in the shade picker; that offset also
 * moves its shadow pair to sum 60, distinct from families 1/2/3 (63/62/61). Family 5 had
 * to change axis entirely — all four residues were spent — so it pins green to 255, which
 * no other family can reach, and rides blue. Its blue starts at 6, not 2, so its shadow is
 * never {@code (63, 0)}, which is family 1's slot-63 shadow.
 *
 * <h2>Shadow needs nothing from us, and that is worth stating</h2>
 * There is deliberately no "am I in a shadowed context" argument here. Minecraft draws the
 * drop shadow itself by quartering every byte ({@code (rgb & 0xFCFCFC) >>> 2}), so the
 * server never emits a shadow colour — the shader's {@code shadowMarkerSlot()} exists to
 * recognise what the <em>client</em> derived. The five families are designed so those
 * quartered pairs stay unique too, and {@code ThemeMarkersTest} walks that second decode
 * as well as the first. {@link #shadowOf(int)} is exposed for exactly that.
 *
 * <h2>Why this is off by default</h2>
 * A player without the pack does not see a theme — they see the marker colours raw, which
 * is confetti. See {@link CosmeticDials#clientThemes}.
 */
public final class ThemeMarkers {

    /**
     * Which way the phase travels. {@code true} adds 1 to the slot, and the shader reads
     * an odd slot as {@code direction = -1}.
     *
     * <p>A constant rather than a dial because it is Shadow Prison's shipped default
     * ({@code ChatDisplay.animatedRgbLeftToRight}) and matching it is the only way a
     * ported theme looks like the theme it was ported from. It is also half the slot
     * space: flipping it server-wide moves every marker onto a different byte pair, so it
     * is not a thing to toggle casually.
     */
    private static final boolean LEFT_TO_RIGHT = true;

    /** Slots 0–63. */
    private static final int FAMILY2_BASE = 64;
    /** Family 2 tops out at slot 62 — its blue, {@code 250 - 4s}, goes negative at 63. */
    private static final int FAMILY3_BASE = 127;
    /** Family 3 tops out at t=61, where blue {@code 246 - 4t} reaches 2. */
    private static final int FAMILY4_BASE = 189;
    /** Family 4 tops out at u=59, where blue {@code 238 - 4u} reaches 2. */
    private static final int FAMILY5_BASE = 249;

    /**
     * The last slot any family can express: family 5 at j=62, blue 254.
     *
     * <p>Slot 311 is shader index 155, so this only trips if somebody adds a 156th theme
     * without opening a family 6. We are at 141.
     */
    public static final int MAX_SLOT = FAMILY5_BASE + 62;

    /** One-shot latch so a bad index cannot spam the console once per chat line. */
    private static final AtomicBoolean CEILING_WARNED = new AtomicBoolean();

    private ThemeMarkers() {
    }

    /**
     * Can this theme be handed to the shader at all?
     *
     * <p><b>No, for every Still theme</b> ({@link ChatTheme.Source#STILL}, indices 900+),
     * and that is not an omission to fix later. The pack's palette ladder is an if-chain
     * over indices 0–141 that ends in a fallback, and its motion table is another one — so
     * a Still theme would not "have no shader support", it would render as Shunt's
     * amethyst grey with somebody else's movement on it. Worse, index 900 encodes to slot
     * 1801, which is past {@link #MAX_SLOT} and would come out as Prismatic. Silently
     * wearing the wrong theme is a support ticket nobody can diagnose from a screenshot.
     * The Still's own five stay on the server-side path, which draws them correctly.
     */
    public static boolean supports(ChatTheme theme) {
        return theme != null
                && theme.source() == ChatTheme.Source.SHADOW
                && theme.index() >= 0
                && slotOf(theme) <= MAX_SLOT;
    }

    /** The marker slot a theme occupies. Public because the round-trip test decodes it. */
    public static int slotOf(ChatTheme theme) {
        return theme.index() * 2 + (LEFT_TO_RIGHT ? 1 : 0);
    }

    /**
     * The text, one component per glyph, each in its marker colour.
     *
     * <p>Iterated over <b>code points</b>, not chars: a surrogate pair split across two
     * components is two broken glyphs, and emoji do reach chat.
     */
    public static Component encode(String text, ChatTheme theme, boolean bold) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        int greenBlue = markerBytes(slotOf(theme), theme.id());

        Component result = Component.empty();
        int[] codePoints = text.codePoints().toArray();
        // max(1) guards a length-0 divide; the loop below cannot run in that case anyway,
        // but the source computed it here and the phase spacing must match byte for byte.
        int length = Math.max(1, codePoints.length);
        for (int i = 0; i < codePoints.length; i++) {
            // i / length, NOT i / (length - 1). The last glyph therefore stops short of a
            // full cycle, which is what stops the first and last characters of a short
            // word landing on the same colour.
            int phase = Math.round(255.0f * i / length);
            TextColor marker = TextColor.color((phase << 16) | greenBlue);
            Component glyph = Component.text(new String(Character.toChars(codePoints[i])), marker)
                    .decoration(TextDecoration.BOLD, bold)
                    .decoration(TextDecoration.ITALIC, false);
            result = result.append(glyph);
        }
        return result.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * The green and blue bytes for a slot, packed as {@code 0xGGBB}.
     *
     * @param themeId only for the log line, so a ceiling breach names the culprit
     */
    public static int markerBytes(int slot, String themeId) {
        int green;
        int blue;
        if (slot < FAMILY2_BASE) {
            green = 1 + slot * 4;
            blue = 254 - slot * 4;
        } else if (slot < FAMILY3_BASE) {
            int s = slot - FAMILY2_BASE;
            green = 3 + s * 4;
            blue = 250 - s * 4;
        } else if (slot < FAMILY4_BASE) {
            int t = slot - FAMILY3_BASE;
            green = 2 + t * 4;
            blue = 246 - t * 4;
        } else if (slot < FAMILY5_BASE) {
            int u = slot - FAMILY4_BASE;
            green = 4 + u * 4;
            blue = 238 - u * 4;
        } else {
            int j = slot - FAMILY5_BASE;
            green = 255;
            blue = 6 + j * 4;
        }
        // FAIL SOFT, never throw. This runs on the chat render path, so an exception here
        // eats a player's whole message. Log once and fall back to family 1 slot 0, which
        // is Prismatic — wrong, but readable, and loud in the log rather than silent.
        //
        // The source guarded `blue < 0 || green > 255` and could not catch its OWN family 5
        // overflow: green there is pinned to 255 and blue climbs, so slot 312 produced
        // blue 258 and quietly carried into the green byte. `blue > 255` is the missing arm.
        if (blue < 0 || blue > 255 || green > 255) {
            if (CEILING_WARNED.compareAndSet(false, true)) {
                Logger.getLogger("TheStill").severe("[theme-markers] theme '" + themeId
                        + "' encodes to marker slot " + slot + ", past the family-5 ceiling"
                        + " of " + MAX_SLOT + " — it will render as Prismatic until a"
                        + " family 6 is opened in BOTH this class and rendertype_text.vsh.");
            }
            green = 1;
            blue = 254;
        }
        return (green << 8) | blue;
    }

    /**
     * What Minecraft turns a colour into when it draws the drop shadow beneath it.
     *
     * <p>Every byte is masked to a multiple of four and then quartered. Not a guess — it
     * is what the shader's {@code shadowMarkerSlot()} was written against, and the whole
     * reason the family bases were chosen the way they were.
     */
    public static int shadowOf(int rgb) {
        return (rgb & 0xFCFCFC) >>> 2;
    }
}
