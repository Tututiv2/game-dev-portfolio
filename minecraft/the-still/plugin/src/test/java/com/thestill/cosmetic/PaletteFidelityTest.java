package com.thestill.cosmetic;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Palette} is a transcription, and this is what stops it drifting back into a
 * design.
 *
 * <p>Every number here was read out of
 * {@code ../Minecraft game im making/plugin/src/main/java/com/yourgame/ui/NickMenu.java}
 * and {@code ChatColorMenu.java}. A previous pass cut the palette to seven families and
 * moved the rows off the frame — sensible engineering, and exactly the change the owner
 * rejected, because what they asked for was their menu. <b>A failure here is very
 * probably this test being right.</b> If the source itself has genuinely changed, diff
 * against it and update both files together.
 */
class PaletteFidelityTest {

    /** {@code NickMenu.java:61-72}. */
    private static final String[] NICK_IDS = {
            "white", "red", "orange", "yellow", "lime", "aqua", "blue", "purple", "pink", "black"
    };

    /** {@code ChatColorMenu.java:38-49} — nine the same, and a tenth that is not. */
    private static final String[] CHAT_IDS = {
            "white", "red", "orange", "yellow", "lime", "aqua", "blue", "purple", "pink", "gray"
    };

    @Test
    @DisplayName("TEN families on each page — not seven, not nine")
    void tenFamilies() {
        assertEquals(10, Palette.NICK.size(),
                "the nickname palette is ten wools in the source; trimming it is the "
                        + "change that got this menu rejected");
        assertEquals(10, Palette.CHAT.size(), "so is the chat palette");
    }

    @Test
    @DisplayName("the two palettes are the source's two palettes, in order")
    void familyOrder() {
        assertArrayEquals(NICK_IDS, Palette.NICK.stream().map(Palette.Family::id)
                .toArray(String[]::new));
        assertArrayEquals(CHAT_IDS, Palette.CHAT.stream().map(Palette.Family::id)
                .toArray(String[]::new));
    }

    @Test
    @DisplayName("the tenth family differs between the two menus, and that is the source")
    void theTenthFamilyDiffers() {
        Palette.Family nick = Palette.NICK.get(9);
        Palette.Family chat = Palette.CHAT.get(9);
        assertEquals("black", nick.id());
        assertEquals("#555555", nick.hex());
        assertEquals(Material.BLACK_WOOL, nick.wool());
        assertEquals("gray", chat.id());
        assertEquals("#AAAAAA", chat.hex());
        assertEquals(Material.GRAY_WOOL, chat.wool());
        assertNotEquals(nick.id(), chat.id(),
                "NickMenu ends in black and ChatColorMenu ends in grey. Two tables, "
                        + "not one — merging them changes what one of the pages looks like");
    }

    @Test
    @DisplayName("every main-page hex is the source's")
    void hexes() {
        String[] expected = {
                "#FFFFFF", "#FF5555", "#FFAA00", "#FFFF55", "#55FF55",
                "#55FFFF", "#5555FF", "#AA00AA", "#FF55FF", "#555555"
        };
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], Palette.NICK.get(i).hex(),
                    Palette.NICK.get(i).id() + " is the wrong colour");
        }
    }

    @Test
    @DisplayName("the wools are the source's wools")
    void wools() {
        Material[] expected = {
                Material.WHITE_WOOL, Material.RED_WOOL, Material.ORANGE_WOOL,
                Material.YELLOW_WOOL, Material.LIME_WOOL, Material.LIGHT_BLUE_WOOL,
                Material.BLUE_WOOL, Material.PURPLE_WOOL, Material.PINK_WOOL,
                Material.BLACK_WOOL
        };
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], Palette.NICK.get(i).wool(),
                    // The one worth naming: "aqua" is LIGHT_BLUE_WOOL, not CYAN_WOOL.
                    Palette.NICK.get(i).id() + " is on the wrong wool");
        }
    }

    @Test
    @DisplayName("the slot tables are NickMenu:90-91, ChatColorMenu:51 and NickMenu:324")
    void slotTables() {
        assertArrayEquals(new int[]{19, 20, 21, 22, 23, 24, 25, 26, 27, 28},
                Palette.PRIMARY_SLOTS);
        assertArrayEquals(new int[]{37, 38, 39, 40, 41, 42, 43, 44, 45, 46},
                Palette.END_SLOTS);
        assertArrayEquals(new int[]{20, 21, 22, 23, 24, 25, 26, 27, 28, 29},
                Palette.COLOR_SLOTS);
        assertArrayEquals(new int[]{10, 11, 13, 15, 16}, Palette.SHADE_SLOTS);
        assertArrayEquals(new int[]{10, 12, 14, 16}, Palette.ARCHIVE_SLOTS);
    }

    /**
     * The rows overflow onto the border frame, and that is not a bug to fix.
     *
     * <p>A six-row chest is nine columns; column 0 and column 8 are frame. Ten swatches
     * starting at 19 therefore put one on the right-hand frame column and one on the next
     * row's left-hand frame column. Moving them so they stop doing that is precisely what
     * was done last time and precisely what was rejected — so it is asserted, in the
     * direction of "this must keep happening".
     */
    @Test
    @DisplayName("the colour rows DO run onto the frame, exactly as the source does")
    void rowsOverflowTheFrameOnPurpose() {
        assertEquals(List.of(26, 27), onFrame(Palette.PRIMARY_SLOTS));
        assertEquals(List.of(44, 45), onFrame(Palette.END_SLOTS));
        assertEquals(List.of(26, 27), onFrame(Palette.COLOR_SLOTS));
    }

    private static List<Integer> onFrame(int[] slots) {
        List<Integer> out = new ArrayList<>();
        for (int slot : slots) {
            int column = slot % 9;
            if (column == 0 || column == 8) {
                out.add(slot);
            }
        }
        return out;
    }

    @Test
    @DisplayName("five shades per nickname family, lightest first")
    void fiveShades() {
        assertEquals(List.of("Lightest", "Light", "Classic", "Dark", "Darkest"),
                Palette.SHADE_NAMES);
        for (Palette.Family family : Palette.NICK) {
            assertEquals(5, family.shades().size(),
                    family.id() + " must carry five shades — NickMenu.java:76-86");
            assertTrue(family.hasShades());
        }
    }

    /**
     * <b>The one place the source contradicts itself, recorded rather than repaired.</b>
     *
     * <p>{@code SHADES[id][2]} is labelled "Classic" and for nine of the ten families it
     * is the same hex as the swatch on the main page — so a right-click, then the middle
     * tile, gets you back where you started. White's does not: the swatch is
     * {@code #FFFFFF} and its Classic is {@code #CFCFCF}, a visibly grey white. Making
     * them agree would be a one-character fix and it would also be a change to how the
     * owner's menu looks, so the discrepancy is asserted instead.
     */
    @Test
    @DisplayName("Classic is the main-page colour — for nine families, and white is the one that isn't")
    void classicMatchesTheSwatchExceptForWhite() {
        List<String> mismatched = new ArrayList<>();
        for (Palette.Family family : Palette.NICK) {
            if (!family.hex().equalsIgnoreCase(family.classic())) {
                mismatched.add(family.id() + " (" + family.hex() + " vs " + family.classic() + ")");
            }
        }
        assertEquals(List.of("white (#FFFFFF vs #CFCFCF)"), mismatched,
                "the source's only Classic/swatch disagreement is white. A NEW one means "
                        + "somebody edited a shade table; a MISSING one means somebody "
                        + "'fixed' white, which changes what the page looks like");
    }

    @Test
    @DisplayName("the chat palette's grey carries no shade page, because the source gives it none")
    void greyHasNoShades() {
        Palette.Family grey = Palette.CHAT.get(9);
        assertFalse(grey.hasShades(),
                "SHADES has no \"gray\" key (NickMenu.java:76-86), and chat has no "
                        + "right-click at all — ChatColorMenu.colorButton says only "
                        + "\"Click to select\"");
        assertEquals("#AAAAAA", grey.classic(),
                "with no shade table, classic() has to fall back to the swatch");
    }

    @Test
    @DisplayName("the four Archive gradients are ArchivistService.java:92-97")
    void archiveShades() {
        assertEquals(4, Palette.ARCHIVE_SHADES.size());
        assertEquals(List.of("Archive Gold", "Void Witness", "Ember", "Abyss"),
                Palette.ARCHIVE_SHADES.stream().map(Palette.ArchiveShade::label).toList());
        assertEquals("#C9A227", Palette.ARCHIVE_SHADES.get(0).startHex());
        assertEquals("#FFF3C4", Palette.ARCHIVE_SHADES.get(0).endHex());
        assertEquals("#0D0D2B", Palette.ARCHIVE_SHADES.get(3).startHex());
        assertEquals("#4A0072", Palette.ARCHIVE_SHADES.get(3).endHex());
    }

    @Test
    @DisplayName("every hex is a real #RRGGBB")
    void hexesParse() {
        List<Palette.Family> all = new ArrayList<>(Palette.NICK);
        all.addAll(Palette.CHAT);
        for (Palette.Family family : all) {
            assertTrue(family.hex().matches("#[0-9A-Fa-f]{6}"), family.hex());
            for (String shade : family.shades()) {
                assertTrue(shade.matches("#[0-9A-Fa-f]{6}"), shade);
            }
        }
        for (Palette.ArchiveShade shade : Palette.ARCHIVE_SHADES) {
            assertTrue(shade.startHex().matches("#[0-9A-Fa-f]{6}"), shade.startHex());
            assertTrue(shade.endHex().matches("#[0-9A-Fa-f]{6}"), shade.endHex());
        }
    }
}
