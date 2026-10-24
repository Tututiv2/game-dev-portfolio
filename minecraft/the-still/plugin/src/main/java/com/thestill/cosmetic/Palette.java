package com.thestill.cosmetic;

import org.bukkit.Material;

import java.util.List;

/**
 * The flat colours, transcribed from Shadow Prison.
 *
 * <p><b>This is a copy, not a design.</b> An earlier pass here cut the palette from ten
 * families to seven and moved the rows so they would stop overflowing onto the border
 * frame. Both were defensible and both were wrong: the owner asked for their menu, and
 * the ten families and the overflowing rows <em>are</em> their menu. Everything below is
 * the value that is in the source file, at the slot that is in the source file.
 *
 * <h2>The two tables really are different, and it is not a typo</h2>
 * {@code NickMenu.java:61-72} and {@code ChatColorMenu.java:38-49} declare the same
 * record with the same nine leading entries and <b>a different tenth</b> — the nickname
 * palette ends in {@code black / #555555 / BLACK_WOOL}, the chat palette ends in
 * {@code gray / #AAAAAA / GRAY_WOOL}. So there are two lists here. Merging them would
 * change what one of the two menus looks like, which is the thing being corrected.
 *
 * <h2>The shade families belong to the nickname menu only</h2>
 * {@code NickMenu.java:76-86} carries five shades for each of its ten ids. Chat has no
 * shade pages at all over there — {@code ChatColorMenu.colorButton} says only
 * "Click to select" — so {@link #CHAT}'s {@code gray} has no shade family, exactly as
 * {@code SHADES} has no {@code "gray"} key.
 *
 * <h2>Classic is usually the main-palette colour, and once it is not</h2>
 * {@code SHADES[id][2]} is labelled "Classic" and for nine families it is the same hex as
 * the swatch on the main page. <b>White's is not</b> — the swatch is {@code #FFFFFF} and
 * its Classic is {@code #CFCFCF}. That is the source's value and it is reproduced here;
 * {@code PaletteFidelityTest} asserts the rule and names white as the one exception, so
 * the discrepancy is recorded rather than quietly repaired.
 */
public final class Palette {

    /**
     * One swatch and its shade page.
     *
     * @param id     lowercase, as the source keys its {@code SHADES} map
     * @param name   what the tile says — the source builds it by upper-casing {@code id}
     * @param wool   the tile, and the tile every one of its shades is drawn on
     * @param hex    the main-page colour, from the {@code COLORS} array
     * @param shades five, lightest first; empty for a family the source gave none
     */
    public record Family(String id, String name, Material wool, String hex,
                         List<String> shades) {

        /** {@code SHADES[id][2]} — the middle step, which the source labels "Classic". */
        public String classic() {
            return shades.isEmpty() ? hex : shades.get(2);
        }

        public boolean hasShades() {
            return !shades.isEmpty();
        }
    }

    // ---- the shade tables, NickMenu.java:76-86 verbatim ---------------------

    private static final List<String> WHITE = List.of("#FFFFFF", "#E8E8E8", "#CFCFCF", "#B5B5B5", "#9C9C9C");
    private static final List<String> RED = List.of("#FFB3B3", "#FF7F7F", "#FF5555", "#E03131", "#A61E1E");
    private static final List<String> ORANGE = List.of("#FFD9A0", "#FFC266", "#FFAA00", "#E08900", "#A66600");
    private static final List<String> YELLOW = List.of("#FFFFB3", "#FFFF80", "#FFFF55", "#E6D22E", "#B3A125");
    private static final List<String> LIME = List.of("#B3FFB3", "#80FF80", "#55FF55", "#2EE62E", "#1FA61F");
    private static final List<String> AQUA = List.of("#B3FFFF", "#80FFFF", "#55FFFF", "#2ED4E6", "#1F9BA6");
    private static final List<String> BLUE = List.of("#9F9FFF", "#7A7AFF", "#5555FF", "#3B3BD9", "#2727A6");
    private static final List<String> PURPLE = List.of("#E39FE3", "#CC55CC", "#AA00AA", "#8A008A", "#5C005C");
    private static final List<String> PINK = List.of("#FFB3FF", "#FF80FF", "#FF55FF", "#E03BE0", "#A62BA6");
    private static final List<String> BLACK = List.of("#8C8C8C", "#707070", "#555555", "#3B3B3B", "#1F1F1F");

    /** {@code NickMenu.java:88}, verbatim. */
    public static final List<String> SHADE_NAMES =
            List.of("Lightest", "Light", "Classic", "Dark", "Darkest");

    // ---- the two palettes --------------------------------------------------

    /** {@code NickMenu.java:61-72}, verbatim, with its shade families attached. */
    public static final List<Family> NICK = List.of(
            new Family("white", "White", Material.WHITE_WOOL, "#FFFFFF", WHITE),
            new Family("red", "Red", Material.RED_WOOL, "#FF5555", RED),
            new Family("orange", "Orange", Material.ORANGE_WOOL, "#FFAA00", ORANGE),
            new Family("yellow", "Yellow", Material.YELLOW_WOOL, "#FFFF55", YELLOW),
            new Family("lime", "Lime", Material.LIME_WOOL, "#55FF55", LIME),
            new Family("aqua", "Aqua", Material.LIGHT_BLUE_WOOL, "#55FFFF", AQUA),
            new Family("blue", "Blue", Material.BLUE_WOOL, "#5555FF", BLUE),
            new Family("purple", "Purple", Material.PURPLE_WOOL, "#AA00AA", PURPLE),
            new Family("pink", "Pink", Material.PINK_WOOL, "#FF55FF", PINK),
            new Family("black", "Black", Material.BLACK_WOOL, "#555555", BLACK));

    /**
     * {@code ChatColorMenu.java:38-49}, verbatim.
     *
     * <p>Nine entries identical to {@link #NICK} and a tenth that is not: grey, where the
     * nickname palette has black. {@code SHADES} has no {@code "gray"} key, so this
     * family carries none and the chat menu offers no right-click — which is what the
     * source does.
     */
    public static final List<Family> CHAT = List.of(
            NICK.get(0), NICK.get(1), NICK.get(2), NICK.get(3), NICK.get(4),
            NICK.get(5), NICK.get(6), NICK.get(7), NICK.get(8),
            new Family("gray", "Gray", Material.GRAY_WOOL, "#AAAAAA", List.of()));

    // ---- the slot tables ---------------------------------------------------

    /**
     * {@code NickMenu.java:90}, verbatim — <b>and it overflows the frame on purpose</b>.
     *
     * <p>A six-row chest is nine columns; the interior is columns 1–7. Ten swatches
     * starting at 19 therefore run 19–25 across the interior, put 26 on the right-hand
     * frame column, 27 on the <em>next</em> row's left-hand frame column, and 28 back
     * inside. That is what the source looks like. Do not tidy it.
     */
    public static final int[] PRIMARY_SLOTS = {19, 20, 21, 22, 23, 24, 25, 26, 27, 28};

    /** {@code NickMenu.java:91}, verbatim. 44 and 45–46 spill the same way. */
    public static final int[] END_SLOTS = {37, 38, 39, 40, 41, 42, 43, 44, 45, 46};

    /** {@code ChatColorMenu.java:51}, verbatim. Starts one slot later than the nick row. */
    public static final int[] COLOR_SLOTS = {20, 21, 22, 23, 24, 25, 26, 27, 28, 29};

    /**
     * {@code NickMenu.java:324}, verbatim — five tiles with gaps at 12 and 14.
     *
     * <p>The gaps are not a mistake to close up. They are what stops the five shades of
     * one colour reading as a single block of one colour.
     */
    public static final int[] SHADE_SLOTS = {10, 11, 13, 15, 16};

    /** {@code NickMenu.java:360}, verbatim — four archive tiles, every other slot. */
    public static final int[] ARCHIVE_SLOTS = {10, 12, 14, 16};

    // ---- the archive gradients ---------------------------------------------

    /**
     * One of the four exclusive two-colour gradients behind the LECTERN on the nickname
     * page.
     *
     * @param id       the catalogue row that unlocks it, over there
     * @param label    what the tile says, drawn in its own gradient
     * @param startHex first stop
     * @param endHex   second stop
     * @param wool     the tile
     */
    public record ArchiveShade(String id, String label, String startHex, String endHex,
                               Material wool) {
    }

    /**
     * {@code ArchivistService.java:92-97}, verbatim.
     *
     * <p>Over there these are bought with Archive Points from an NPC on the Skydeck. There
     * is no such NPC here, so every tile renders in the source's own <b>unowned</b> state
     * — which is a real state of that code, not an invention: {@code NickMenu.java:365}
     * computes ownership as {@code archivistService != null && ...} and its service is
     * setter-injected, so a server that never injected one draws exactly this page.
     */
    public static final List<ArchiveShade> ARCHIVE_SHADES = List.of(
            new ArchiveShade("shade_archive_gold", "Archive Gold", "#C9A227", "#FFF3C4",
                    Material.ORANGE_WOOL),
            new ArchiveShade("shade_void_witness", "Void Witness", "#E6D5FF", "#9B59B6",
                    Material.MAGENTA_WOOL),
            new ArchiveShade("shade_ember", "Ember", "#FF4500", "#FFD700",
                    Material.RED_WOOL),
            new ArchiveShade("shade_abyss", "Abyss", "#0D0D2B", "#4A0072",
                    Material.BLACK_WOOL));

    private Palette() {
    }

    /** A nickname family by its source id, or white. */
    public static Family byId(String id) {
        for (Family family : NICK) {
            if (family.id().equals(id)) {
                return family;
            }
        }
        return NICK.get(0);
    }
}
