package com.thestill.cosmetic;

import com.thestill.state.StateKey;

/**
 * Everything {@code /nick} and {@code /chatcolor} remember.
 *
 * <p>Eleven keys and not one column. Shadow Prison put the same eleven on its
 * {@code PlayerProfile} as fields — {@code nickText}, {@code nickColorStart},
 * {@code nickGradient}, {@code chatRgbTheme}, {@code nametagMatchChat} and the rest —
 * which is a large part of why that class reached 961 lines and ninety fields and
 * became the file nobody would touch. Phase 0's rule 3 exists so that a cosmetic
 * feature costs a constant.
 *
 * <p><b>Both surfaces store the same three things</b> — which theme, whether it is on,
 * and (for the tag) whether it is borrowed from the other surface. That symmetry is what
 * lets one grid menu serve both; see {@link Target}.
 *
 * <p><b>The stored value is a wish, not a permission.</b> Every one of these survives a
 * rank lapsing, and is deliberately never cleared when one does — see
 * {@link NickRender}, whose whole job is to render from the live perk while leaving what
 * the player chose exactly where they left it. Somebody who re-buys a rank gets their
 * name back rather than a blank form.
 */
public final class CosmeticKeys {

    /** The custom name. Blank means "use my username", which is the common case. */
    public static final StateKey<String> NICK_TEXT = StateKey.ofString("nick.text", "");

    /** Start colour as {@code #RRGGBB}. Blank falls back to white. */
    public static final StateKey<String> NICK_START = StateKey.ofString("nick.start", "");

    /** End colour. Only read when {@link #NICK_GRADIENT} is set AND the perk is live. */
    public static final StateKey<String> NICK_END = StateKey.ofString("nick.end", "");

    public static final StateKey<Boolean> NICK_GRADIENT =
            StateKey.ofBoolean("nick.gradient", false);

    /**
     * When the name was last changed, epoch millis.
     *
     * <p>This is the cooldown, and the cooldown is not a politeness setting: a name is
     * the only free-text a player can put in front of everybody else, and one that can
     * be changed every tick is a name that can be used to dodge a report. Somebody who
     * says something under a name has to wear it long enough to be named for it.
     */
    public static final StateKey<Long> NICK_SET_AT = StateKey.ofLong("nick.set-at", 0L);

    /** Static chat colour as {@code #RRGGBB}. Blank means the server default. */
    public static final StateKey<String> CHAT_COLOUR = StateKey.ofString("chat.colour", "");

    /**
     * Bold chat. <b>Admin only, and it is a preference rather than a permission.</b>
     *
     * <p>Shadow Prison's {@code /chatcolor} has a bold toggle on slot 39 that only an
     * admin ever sees ({@code ChatColorMenu.java:176-189}), and its renderer re-checks the
     * permission every single time it draws a line — "a stale saved preference must never
     * grant bold chat to a regular player" ({@code ChatDisplay.java:53}). Both halves are
     * copied: the tile is drawn only for admins, and {@link NickRender#chat} is handed the
     * flag by a caller that has the {@link org.bukkit.entity.Player} and can ask.
     */
    public static final StateKey<Boolean> CHAT_BOLD =
            StateKey.ofBoolean("chat.bold", false);

    public static final StateKey<String> CHAT_THEME = StateKey.ofString("chat.theme", "");

    public static final StateKey<Boolean> CHAT_THEME_ON =
            StateKey.ofBoolean("chat.theme-on", false);

    public static final StateKey<String> TAG_THEME = StateKey.ofString("tag.theme", "");

    public static final StateKey<Boolean> TAG_THEME_ON =
            StateKey.ofBoolean("tag.theme-on", false);

    /**
     * The nametag borrows the chat theme instead of carrying its own.
     *
     * <p>Kept because it is the setting most people actually want — one look, everywhere
     * — and because without it the two surfaces drift and a player reads as two
     * different people in tab and in chat.
     *
     * <p>Deliberately <b>one</b> direction, unlike the source, which had both this and a
     * "chat follows nametag" toggle. Two mutual follow-flags is a cycle waiting for
     * somebody to switch both on, and neither of them is more useful than the other.
     */
    public static final StateKey<Boolean> TAG_MATCH_CHAT =
            StateKey.ofBoolean("tag.match-chat", false);

    private CosmeticKeys() {
    }
}
