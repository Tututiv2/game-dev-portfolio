package com.thestill.role;

import java.util.List;
import java.util.Set;

/**
 * What a paid rank is allowed to give you.
 *
 * <p><b>This is an allow-list, and that is the entire point.</b> The design law says
 * "no pay-to-win — ranks and roles are cosmetic only, forever". A law written in a
 * document is a law somebody breaks at 2am while editing YAML; a law written as a set
 * that a unit test walks is a law that cannot be broken without deleting the test.
 *
 * <p><b>Why this is not paranoia.</b> Shadow Prison's role config sold three perks that
 * bought progression outright — {@code rankup-discount-percent} (cheaper ranks),
 * {@code blocks-per-key-bonus} (faster crate rate) and {@code vault-slot-bonus} (more
 * storage). Two of them have since been taken out of its live config by hand. The
 * point is not that its owner got it wrong; the point is that it took a manual edit to
 * a running server to fix, and nothing stopped it going in.
 *
 * <p>In this game it would be worse. The Still's economy <em>is</em> a perception
 * channel — noise calls them, heat keeps them — so a purchasable muffle, a seal bonus,
 * a Din discount or a quieter winch is not merely an advantage, it is <em>the game</em>
 * for sale. There is no version of that which is only a bit pay-to-win.
 *
 * <p><b>Adding a perk:</b> add it here in the same commit that adds it to
 * {@code roles.yml}, and say in one line why it cannot affect ore, money, the ladder,
 * the Din, the heat or the seal. If you cannot write that line, it does not go in.
 */
public final class Perks {

    /** Recolour your own name. */
    public static final String NICK = "nick";

    /** Actually change your name, rather than only its colour. */
    public static final String NICK_RENAME = "nick-rename";

    /** Two-colour gradient names. */
    public static final String NICK_GRADIENT = "nick-gradient";

    /** Coloured chat. */
    public static final String CHAT_COLOUR = "chat-colour";

    /** Animated chat, once there is an engine for it. */
    public static final String CHAT_ANIMATED = "chat-animated";

    /** An animated role tag rather than a flat one. */
    public static final String TAG_ANIMATED = "tag-animated";

    /** A block on your head. */
    public static final String HAT = "hat";

    /** A particle trail. */
    public static final String TRAIL = "trail";

    /** Your own join line. */
    public static final String JOIN_MESSAGE = "join-message";

    /** Sit on stairs and slabs. */
    public static final String SIT = "sit";

    /**
     * Everything a role may carry.
     *
     * <p>Every entry is something a player could turn off and still be playing exactly
     * the same game. That is the test to apply before adding one.
     */
    public static final Set<String> ALLOWED = Set.of(
            NICK, NICK_RENAME, NICK_GRADIENT,
            CHAT_COLOUR, CHAT_ANIMATED, TAG_ANIMATED,
            HAT, TRAIL, JOIN_MESSAGE, SIT);

    /**
     * Perks that are banned by name, so the failure message can say why.
     *
     * <p>The allow-list already refuses these. This exists so that somebody who copies
     * a row out of Shadow Prison's config gets told what is wrong with it rather than
     * "unknown perk", which reads like a typo.
     */
    public static final Set<String> BANNED = Set.of(
            "rankup-discount-percent",   // buys the ladder
            "blocks-per-key-bonus",      // buys drop rate
            "vault-slot-bonus",          // buys storage
            "din-reduction",             // buys the core mechanic
            "heat-reduction",            // buys the other half of it
            "seal-bonus",                // buys the thesis
            "sell-multiplier",           // buys the economy
            "mining-speed",              // buys the loop
            "winch-quiet");              // buys the drama

    /**
     * The store's display order, most exciting first, with a colour band each.
     *
     * <p>Copied in shape from Shadow Prison's {@code PERK_LINE_ORDER}, because the
     * thing that makes its rank tiles read well is not the words — it is that the
     * list is <b>ordered by desirability and colour-graded</b>, so the eye lands on the
     * headline perk first and the utility ones read as a tail. A flat grey list of
     * bullets in declaration order is what a tile looks like when nobody decided which
     * part was the point.
     */
    public static final List<String> DISPLAY_ORDER = List.of(
            TAG_ANIMATED, CHAT_ANIMATED, NICK_GRADIENT, NICK_RENAME,
            CHAT_COLOUR, NICK, TRAIL, HAT, JOIN_MESSAGE, SIT);

    /** The colour band a perk reads in. Pink is the headline, green is the tail. */
    public static net.kyori.adventure.text.format.TextColor colourOf(String perk) {
        return switch (perk) {
            case TAG_ANIMATED, CHAT_ANIMATED -> net.kyori.adventure.text.format.TextColor.color(0xFF6FD8);
            case NICK_GRADIENT, NICK_RENAME -> net.kyori.adventure.text.format.TextColor.color(0x6FD8FF);
            case CHAT_COLOUR, NICK -> net.kyori.adventure.text.format.TextColor.color(0xFFD86F);
            default -> net.kyori.adventure.text.format.TextColor.color(0x8FE08F);
        };
    }

    /**
     * What each perk says on the store tile.
     *
     * <p>Marketing copy, and every line is deliberately about how you <em>look</em>.
     * If a line here could be read as "this makes you better at the game", the perk
     * should not exist — that is the allow-list's job, and this is where a breach
     * would be most obvious to whoever writes it.
     */
    public static String describe(String perk) {
        return switch (perk) {
            case TAG_ANIMATED -> "Living RGB rank tag";
            case CHAT_ANIMATED -> "Animated chat themes (/chatcolor)";
            case NICK_GRADIENT -> "Gradient nicknames (/nick)";
            case NICK_RENAME -> "Change your name, not just its colour";
            case CHAT_COLOUR -> "Coloured chat (/chatcolor)";
            case NICK -> "Recolour your own name (/nick)";
            case TRAIL -> "Walking particle trail";
            case HAT -> "Wearable cosmetic hat (/hat)";
            case JOIN_MESSAGE -> "Custom join message";
            case SIT -> "Sit anywhere (/sit)";
            default -> perk;
        };
    }

    private Perks() {
    }
}
