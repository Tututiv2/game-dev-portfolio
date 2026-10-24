package com.thestill.cosmetic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GLSL decoder, re-implemented in Java, pointed back at the Java encoder.
 *
 * <p>This exists because the two halves of this protocol live in different languages, in
 * different repositories' worth of file, and are executed on different machines. Nothing
 * else can tell you they still agree. A wrong byte does not crash and does not log — it
 * renders as green-and-blue confetti in front of every player at once, and the only way
 * to find out is to log in with the pack loaded, which is exactly the loop this project
 * has spent whole sessions failing to close.
 *
 * <p>{@link #decodeNormal} and {@link #decodeShadow} below are transcriptions of
 * {@code normalMarkerSlot()} and {@code shadowMarkerSlot()} in
 * {@code resource-pack/assets/minecraft/shaders/core/rendertype_text.vsh}. <b>They are
 * copied from the shader, never derived from {@link ThemeMarkers}.</b> A decoder written
 * by reading the encoder would agree with a broken encoder, which is a test that always
 * passes. If you change one of these, change it because the .vsh changed.
 */
class ThemeMarkersTest {

    /** {@code ANIMATED_CHAT_MARKER_SLOTS} — 64 per family, and the shader's loop bound. */
    private static final int MARKER_SLOTS = 64;
    private static final int FAMILY2_BASE = 64;
    private static final int FAMILY3_BASE = 127;
    private static final int FAMILY4_BASE = 189;
    private static final int FAMILY5_BASE = 249;

    // ---- the shader, in Java ------------------------------------------------

    /** {@code normalMarkerSlot(vec4)}. -1 for "this is ordinary text, leave it alone". */
    private static int decodeNormal(int rgb) {
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        for (int slot = 0; slot < MARKER_SLOTS; slot++) {
            if (green == 1 + slot * 4 && blue == 254 - slot * 4) {
                return slot;
            }
            if (green == 3 + slot * 4 && blue == 250 - slot * 4) {
                return FAMILY2_BASE + slot;
            }
            if (green == 2 + slot * 4 && blue == 246 - slot * 4) {
                return FAMILY3_BASE + slot;
            }
            if (green == 4 + slot * 4 && blue == 238 - slot * 4) {
                return FAMILY4_BASE + slot;
            }
            if (green == 255 && blue == 6 + slot * 4) {
                return FAMILY5_BASE + slot;
            }
        }
        return -1;
    }

    /** {@code shadowMarkerSlot(vec4)} — what the client's quartered copy decodes to. */
    private static int decodeShadow(int rgb) {
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        for (int slot = 0; slot < MARKER_SLOTS; slot++) {
            if (green == slot && blue == 63 - slot) {
                return slot;
            }
            if (green == slot && blue == 62 - slot) {
                return FAMILY2_BASE + slot;
            }
            if (green == slot && blue == 61 - slot) {
                return FAMILY3_BASE + slot;
            }
            if (green == 1 + slot && blue == 59 - slot) {
                return FAMILY4_BASE + slot;
            }
            if (green == 63 && blue == 1 + slot) {
                return FAMILY5_BASE + slot;
            }
        }
        return -1;
    }

    /** The shader reads the theme off the slot; direction is the odd bit. */
    private static int themeOf(int slot) {
        return slot / 2;
    }

    private static int directionOf(int slot) {
        return slot % 2 == 1 ? -1 : 1;
    }

    // ---- the round trip -----------------------------------------------------

    @Test
    @DisplayName("all 141 themes survive the encode/decode round trip")
    void everyThemeDecodesToItsOwnIndex() {
        assertEquals(141, ShadowThemes.ALL.size(),
                "the pack's palette ladder is written for exactly these themes");
        for (ChatTheme theme : ShadowThemes.ALL) {
            int packed = ThemeMarkers.markerBytes(ThemeMarkers.slotOf(theme), theme.id());
            int slot = decodeNormal(packed);
            assertTrue(slot >= 0, theme.id() + " (index " + theme.index() + ") encodes to "
                    + hex(packed) + ", which the shader does not recognise as a marker at "
                    + "all — the client would draw that colour literally");
            assertEquals(theme.index(), themeOf(slot),
                    theme.id() + " encodes to slot " + slot + ", which the shader reads as "
                            + "theme " + themeOf(slot) + " — that is a DIFFERENT theme's "
                            + "palette and motion, silently");
        }
    }

    @Test
    @DisplayName("the shadow copy the client derives decodes to the same theme")
    void theQuarteredShadowDecodesToo() {
        // Minecraft draws a drop shadow by quartering every byte. The server never sends
        // that colour, so nothing here can catch a mistake in it except this: the five
        // family bases were chosen so the quartered pairs stay unique, and if a base ever
        // moves, half of every glyph goes wrong while the other half looks fine.
        for (ChatTheme theme : ShadowThemes.ALL) {
            int packed = ThemeMarkers.markerBytes(ThemeMarkers.slotOf(theme), theme.id());
            int slot = decodeShadow(ThemeMarkers.shadowOf(packed));
            assertEquals(ThemeMarkers.slotOf(theme), slot,
                    theme.id() + "'s shadow decodes to slot " + slot + " instead of "
                            + ThemeMarkers.slotOf(theme) + " — its shadow would animate as "
                            + "another theme underneath the right one");
        }
    }

    @Test
    @DisplayName("no marker is ambiguous — a shadow never reads as a normal marker")
    void familiesDoNotCollide() {
        // The shader tries normalMarkerSlot FIRST and only falls through to the shadow
        // table if it returns -1. So a shadow byte pair that also matches a normal family
        // is not a tie, it is a shadow rendering at full brightness as the wrong theme.
        // This is what the `green mod 4` residue argument in the .vsh is actually buying.
        Map<Integer, String> markers = new HashMap<>();
        for (ChatTheme theme : ShadowThemes.ALL) {
            int packed = ThemeMarkers.markerBytes(ThemeMarkers.slotOf(theme), theme.id());
            String clash = markers.put(packed, theme.id());
            assertEquals(null, clash, "'" + theme.id() + "' and '" + clash + "' emit the "
                    + "same marker bytes " + hex(packed) + " — indistinguishable on the GPU");

            int shadow = ThemeMarkers.shadowOf(packed);
            assertEquals(-1, decodeNormal(shadow), theme.id() + "'s shadow " + hex(shadow)
                    + " also matches a NORMAL marker family, so the shader would draw the "
                    + "shadow at full brightness as theme " + themeOf(decodeNormal(shadow)));
        }
    }

    @Test
    @DisplayName("direction is consistent for every theme")
    void directionIsWhatTheSourceShipped() {
        // Shadow Prison ships leftToRight = true, i.e. an ODD slot, i.e. direction -1.
        // Half the slot space hangs off this bit: flip it and every marker moves to a
        // different byte pair, so a mismatch here is not "the sweep goes the wrong way",
        // it is 141 themes landing on their neighbours' indices.
        for (ChatTheme theme : ShadowThemes.ALL) {
            int slot = ThemeMarkers.slotOf(theme);
            assertEquals(-1, directionOf(slot), theme.id() + " changed direction");
            assertEquals(theme.index() * 2 + 1, slot);
        }
    }

    @Test
    @DisplayName("index 34 is still a hole, and no index is used twice")
    void theWireProtocolHasNotDrifted() {
        Set<Integer> indices = new HashSet<>();
        for (ChatTheme theme : ShadowThemes.ALL) {
            assertTrue(indices.add(theme.index()), "shader index " + theme.index()
                    + " is used twice, so two themes encode to the same marker");
        }
        assertFalse(indices.contains(34), "index 34 was OBSIDIAN_FLOW, removed for sitting "
                + "too close to the VOID rank. Reusing it repoints the pack's palette "
                + "ladder at a theme that is not there");
        // The pack's if-chain runs 0..141 and then falls through to Shunt. An index past
        // the end does not fail, it silently renders as amethyst grey.
        assertEquals(141, indices.stream().mapToInt(Integer::intValue).max().orElse(-1));
    }

    // ---- what the encoder actually emits ------------------------------------

    @Test
    @DisplayName("one component per glyph, phase climbing, marker bytes constant")
    void encodeShapesTheComponentTheShaderExpects() {
        ChatTheme theme = ShadowThemes.ALL.get(0);
        Component encoded = ThemeMarkers.encode("hello", theme, true);
        List<Component> glyphs = encoded.children();
        assertEquals(5, glyphs.size(), "the shader reads a marker per vertex colour, so "
                + "the text has to be split per character — one component means one colour "
                + "for the whole line and no travelling phase");

        int expected = ThemeMarkers.markerBytes(ThemeMarkers.slotOf(theme), theme.id());
        int previousPhase = -1;
        for (int i = 0; i < glyphs.size(); i++) {
            Component glyph = glyphs.get(i);
            TextColor colour = glyph.color();
            assertNotNull(colour, "glyph " + i + " has no colour, so it is not marked");
            assertEquals(expected, colour.value() & 0xFFFF,
                    "glyph " + i + " carries the wrong marker bytes");

            int phase = (colour.value() >> 16) & 0xFF;
            assertTrue(phase > previousPhase, "the phase must climb across the line — a "
                    + "flat red byte is a line that animates as one block instead of as a "
                    + "wave travelling through it");
            previousPhase = phase;

            assertEquals(TextDecoration.State.TRUE,
                    glyph.decoration(TextDecoration.BOLD), "bold was asked for");
            assertEquals(TextDecoration.State.FALSE,
                    glyph.decoration(TextDecoration.ITALIC), "item names default to italic "
                            + "and a per-glyph component inherits it — the same bug "
                            + "Gradients was written to stop happening eight times");
        }
        assertEquals("h", ((TextComponent) glyphs.get(0)).content());
    }

    @Test
    @DisplayName("a surrogate pair stays one glyph")
    void emojiAreNotSplitInHalf() {
        // Splitting a code point across two components is two broken glyphs, and emoji do
        // reach chat. This is why the encoder walks codePoints() and not charAt().
        Component encoded = ThemeMarkers.encode("a😀b", ShadowThemes.ALL.get(0), false);
        assertEquals(3, encoded.children().size());
        assertEquals("😀", ((TextComponent) encoded.children().get(1)).content());
    }

    @Test
    @DisplayName("The Still's own themes are refused, not mis-encoded")
    void stillThemesNeverReachTheShader() {
        // Index 900 encodes to slot 1801, past every family. Without this gate it would
        // fail soft to Prismatic — the player would wear a real theme, just not theirs,
        // and nothing anywhere would say so.
        for (ChatTheme theme : StillThemes.ALL) {
            assertFalse(ThemeMarkers.supports(theme), theme.id() + " has no entry in the "
                    + "pack's palette ladder; it must stay on the server-side path");
        }
        assertFalse(ThemeMarkers.supports(ThemeLibrary.NONE));
        assertFalse(ThemeMarkers.supports(null));
        for (ChatTheme theme : ShadowThemes.ALL) {
            assertTrue(ThemeMarkers.supports(theme), theme.id() + " lost shader support");
        }
    }

    @Test
    @DisplayName("past the ceiling it falls back to Prismatic instead of throwing")
    void theCeilingFailsSoft() {
        // This runs on the chat render path. An exception here eats a player's whole
        // message, so a 156th theme must cost a log line and a wrong colour, not silence
        // somebody mid-conversation.
        int packed = ThemeMarkers.markerBytes(ThemeMarkers.MAX_SLOT + 1, "impossible");
        assertEquals(0, decodeNormal(packed), "the fallback is family 1 slot 0");
        // And the arm the source was missing: family 5's blue climbs while its green is
        // pinned at 255, so an overflow there carries into the green byte rather than
        // going negative.
        assertEquals(packed, ThemeMarkers.markerBytes(FAMILY5_BASE + 63, "overflow"));
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb);
    }
}
