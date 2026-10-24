package com.thestill.cosmetic;

import com.thestill.role.Perks;
import com.thestill.state.StateKey;

/**
 * Which surface a theme is being picked for.
 *
 * <p><b>This enum is the reason there is one grid menu and not two.</b> Shadow Prison
 * shipped the theme picker twice — once in {@code ChatColorMenu} for chat, once in
 * {@code NickMenu} for the nametag — with two slot tables, two pagination
 * implementations, two category tabs and two copies of the unlock check. They had
 * already drifted: the chat grid omits slot 31 (a control sits on it) and the nametag
 * grid does not, so the two pages hold 27 and 28 themes and the same theme is on a
 * different page depending on which menu you opened it from.
 *
 * <p>The two surfaces differ in exactly four facts — which key stores the choice, which
 * key stores the on/off, which perk opens the surface at all, and which perk opens its
 * moving themes. Those four are here, and everything else is shared.
 */
public enum Target {

    /**
     * Your name: tab list and chat prefix.
     *
     * <p>Not above your head. An above-head name comes from a scoreboard team's prefix
     * and the team's own colour is one of sixteen named ones — a protocol limit, not a
     * Paper gap — so a per-character theme cannot survive up there. See
     * {@link com.thestill.ui.Gradients#nearestNamed}.
     */
    NICKTAG(CosmeticKeys.TAG_THEME, CosmeticKeys.TAG_THEME_ON,
            Perks.NICK, Perks.TAG_ANIMATED,
            "Your name", "tab list and chat"),

    /** Everything you say. */
    CHAT(CosmeticKeys.CHAT_THEME, CosmeticKeys.CHAT_THEME_ON,
            Perks.CHAT_COLOUR, Perks.CHAT_ANIMATED,
            "Your messages", "every line you send");

    private final StateKey<String> themeKey;
    private final StateKey<Boolean> enabledKey;
    private final String basePerk;
    private final String motionPerk;
    private final String label;
    private final String where;

    Target(StateKey<String> themeKey, StateKey<Boolean> enabledKey,
           String basePerk, String motionPerk, String label, String where) {
        this.themeKey = themeKey;
        this.enabledKey = enabledKey;
        this.basePerk = basePerk;
        this.motionPerk = motionPerk;
        this.label = label;
        this.where = where;
    }

    public StateKey<String> themeKey() {
        return themeKey;
    }

    public StateKey<Boolean> enabledKey() {
        return enabledKey;
    }

    /** The perk that opens the surface at all. */
    public String basePerk() {
        return basePerk;
    }

    /** The perk its moving themes are gated on. */
    public String motionPerk() {
        return motionPerk;
    }

    public String label() {
        return label;
    }

    public String where() {
        return where;
    }
}
