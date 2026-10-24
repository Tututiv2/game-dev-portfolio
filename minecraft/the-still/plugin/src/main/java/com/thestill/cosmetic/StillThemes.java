package com.thestill.cosmetic;

import com.thestill.role.Perks;
import org.bukkit.Material;

import java.util.List;

/**
 * The Still's own theme library — five, written for this world rather than ported.
 *
 * <p>They sit beside the Shadow Prison set behind the two-button chooser, and the
 * reason to keep them separate rather than merging the lists is that they are answering
 * different questions. Shadow Prison's are a colour catalogue. These five are the
 * server's own vocabulary: every one is a thing that exists in the fiction, so wearing
 * one says where you have been rather than which swatch you liked.
 *
 * <p>Each is a two-stop sweep because that is what renders without a resource pack —
 * see {@link ChatTheme}. The craft is in the <em>pairing</em>: a theme reads as one
 * material when its two stops are the same hue at different brightness, and as a
 * transition when they are not. Four of these are materials. One is a transition, and
 * it is the expensive one.
 */
public final class StillThemes {

    /**
     * <b>Ember</b> — the server's own colour, and the one everybody can have.
     *
     * <p>The heading burns in this, the welcome banners burn in this, the top rank's
     * name burns in this. Giving it away free is deliberate: a house colour that only
     * paying players can wear stops being the house colour.
     */
    public static final ChatTheme EMBER = new ChatTheme("still_ember", "Ember",
            ChatTheme.Source.STILL, ChatTheme.Motion.MOTION, Material.MAGMA_BLOCK,
            0x14090A, 0x8C2A18, 0xFF8A4D, false, 900, null);

    /**
     * <b>Fogline</b> — the twenty-two blocks you can see, and the wall past them.
     *
     * <p>Almost no contrast on purpose. It is the only theme here that is hard to read
     * at a glance, which is the joke: the fog is what stops you reading things.
     */
    public static final ChatTheme FOGLINE = new ChatTheme("still_fogline", "Fogline",
            ChatTheme.Source.STILL, ChatTheme.Motion.MOTION, Material.WHITE_WOOL,
            0x6E7A83, 0xB8C4CC, 0xF4F8FA, false, 901, Perks.CHAT_COLOUR);

    /**
     * <b>Drowned</b> — the continent under the water, seen from above it.
     *
     * <p>Slow, wide bands. Two cycles would make it flicker; less than one makes it a
     * flat blue. This is the theme that most rewards being read on a long line.
     */
    public static final ChatTheme DROWNED = new ChatTheme("still_drowned", "Drowned",
            ChatTheme.Source.STILL, ChatTheme.Motion.MOTION, Material.PRISMARINE,
            0x061C27, 0x14647D, 0x5FD0E0, false, 902, Perks.CHAT_COLOUR);

    /**
     * <b>Rust</b> — the rig itself, and the only static one.
     *
     * <p>Deliberately a COLOUR theme rather than a MOTION one. Rust does not move, and
     * a library where everything shimmers has no way to say "this one is just a nice
     * colour" — which is most of what people actually want.
     */
    public static final ChatTheme RUST = new ChatTheme("still_rust", "Rust",
            ChatTheme.Source.STILL, ChatTheme.Motion.COLOUR, Material.COPPER_BLOCK,
            0x4A1E0E, 0x8C3B1E, 0xD98C4A, false, 903, Perks.CHAT_COLOUR);

    /**
     * <b>Choir</b> — Din at the top of the scale, and the most expensive thing here.
     *
     * <p>The only theme that is a <em>transition</em> rather than a material: near-black
     * to bone white, at three cycles, so it does not glow — it <b>strobes</b>. It is
     * meant to be slightly too much, because CHOIR is the thirty seconds nobody
     * survives and a tasteful version of that would be a lie.
     *
     * <p>Gated on {@link Perks#CHAT_ANIMATED}, which only the top two ranks carry.
     */
    public static final ChatTheme CHOIR = new ChatTheme("still_choir", "Choir",
            ChatTheme.Source.STILL, ChatTheme.Motion.MOTION, Material.BELL,
            0x14090A, 0xC4231A, 0xFFF4EC, false, 904, Perks.CHAT_ANIMATED);

    /** In menu order: free first, dearest last, so the list reads as a ladder. */
    public static final List<ChatTheme> ALL = List.of(EMBER, RUST, FOGLINE, DROWNED, CHOIR);

    private StillThemes() {
    }
}
