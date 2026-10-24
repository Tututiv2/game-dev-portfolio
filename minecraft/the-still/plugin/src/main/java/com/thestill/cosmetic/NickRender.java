package com.thestill.cosmetic;

import com.thestill.player.Profile;
import com.thestill.role.Perks;
import com.thestill.ui.Gradients;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * A name, and a line of chat, as they are actually drawn.
 *
 * <h2>Law 2: render from the LIVE perk, never from the stored flag alone</h2>
 * Every method here asks the role table what this player may have <em>right now</em>,
 * and treats what is on the profile as a preference rather than as an entitlement.
 *
 * <p>The failure it prevents is the one that only shows up months later. Somebody buys a
 * rank, sets a gradient name, and the rank lapses or is removed by an admin — and the
 * gradient keeps rendering forever, because the flag that turns it on was written once
 * and nothing ever goes back over it. Shadow Prison rendered straight off
 * {@code profile.nickGradient()} and {@code profile.hasNickFormatting()}; the perk was
 * checked at the point of <em>setting</em> and nowhere else, so the check was a door
 * that only had to be walked through once.
 *
 * <p>The other half of the law is that nothing is <b>cleared</b> when a perk goes away.
 * A player who re-buys gets their name back rather than a blank form, and an admin who
 * removes a rank by mistake has not destroyed anything.
 *
 * <p>Precedence when several are set: <b>a live theme beats a gradient beats a solid
 * colour.</b> The most expensive thing somebody is entitled to is the thing they see.
 */
public final class NickRender {

    private static final TextColor FALLBACK = NamedTextColor.WHITE;

    private NickRender() {
    }

    /**
     * The words, without any colour.
     *
     * <p>{@link Perks#NICK_RENAME} is what buys a different name; {@link Perks#NICK} only
     * buys a different colour on the one you have. Somebody holding the cheaper of the
     * two shows their username however much text is stored.
     */
    public static String textOf(Profile profile, String username, ThemeService themes) {
        if (profile == null || themes == null || !themes.has(profile, Perks.NICK_RENAME)) {
            return username;
        }
        String stored = profile.state().get(CosmeticKeys.NICK_TEXT);
        return stored == null || stored.isBlank() ? username : stored;
    }

    /**
     * The name as tab, chat and every menu preview draw it.
     *
     * <p>Not above the head. A scoreboard team can colour a name with one of sixteen
     * named colours and nothing finer — see {@link Gradients#nearestNamed} — so the
     * per-character work here has no way to reach up there.
     */
    public static Component name(Profile profile, String username, ThemeService themes) {
        String text = textOf(profile, username, themes);
        if (profile == null || themes == null || !themes.has(profile, Perks.NICK)) {
            // No nick perk at all: the plain white username, and specifically NOT the
            // stored text — someone whose rank lapsed reverts to who they really are.
            return Component.text(username, FALLBACK)
                    .decoration(TextDecoration.BOLD, false)
                    .decoration(TextDecoration.ITALIC, false);
        }

        ChatTheme theme = themes.live(profile, Target.NICKTAG);
        if (theme != ThemeLibrary.NONE) {
            // Through the service, not the theme: which of the two render paths is live
            // is a server-wide decision and no call site gets to make it. See
            // ThemeService#render.
            return themes.render(theme, text, true);
        }

        TextColor start = Gradients.parse(profile.state().get(CosmeticKeys.NICK_START), FALLBACK);
        if (profile.state().get(CosmeticKeys.NICK_GRADIENT)
                && themes.has(profile, Perks.NICK_GRADIENT)) {
            TextColor end = Gradients.parse(profile.state().get(CosmeticKeys.NICK_END), start);
            if (!start.equals(end)) {
                return Gradients.bold(text, start, end);
            }
        }
        return Component.text(text, start)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * What somebody said, in whatever they are entitled to say it in.
     *
     * <p>Same precedence, same law. A static chat colour needs {@link Perks#CHAT_COLOUR}
     * every time it is drawn, not once when it was picked.
     */
    public static Component chat(Profile profile, String message, ThemeService themes) {
        return chat(profile, message, themes, false);
    }

    /**
     * @param bold the admin-only bold preference, <b>already checked against the live
     *             permission by the caller</b>. It is a parameter rather than a read of
     *             {@link CosmeticKeys#CHAT_BOLD} precisely so that it cannot be honoured
     *             without somebody having asked the {@link org.bukkit.entity.Player}
     *             whether they are still an admin — see {@code ChatDisplay.java:53}, where
     *             the source makes the same point and enforces it the same way
     */
    public static Component chat(Profile profile, String message, ThemeService themes,
                                 boolean bold) {
        if (profile == null || themes == null) {
            return Component.text(message, NamedTextColor.GRAY)
                    .decoration(TextDecoration.BOLD, bold);
        }
        ChatTheme theme = themes.live(profile, Target.CHAT);
        if (theme != ThemeLibrary.NONE) {
            return themes.render(theme, message, bold);
        }
        if (!themes.has(profile, Perks.CHAT_COLOUR)) {
            return Component.text(message, NamedTextColor.GRAY)
                    .decoration(TextDecoration.BOLD, bold);
        }
        return Component.text(message,
                        Gradients.parse(profile.state().get(CosmeticKeys.CHAT_COLOUR),
                                NamedTextColor.GRAY))
                .decoration(TextDecoration.BOLD, bold)
                .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * The theme that is currently swallowing this surface's flat colour, or null.
     *
     * <p><b>This is the answer to "I picked a colour and nothing happened".</b> The
     * precedence in this class is theme &gt; gradient &gt; solid, and it is right — the
     * most expensive thing somebody is entitled to is the thing they see. What was wrong
     * is that nothing ever said it out loud: a player with a theme switched on could
     * click every swatch on both pages, watch each one save correctly, and see no change
     * anywhere, because the theme above it wins every time. Three symptoms were reported
     * on 2026-08-05 and this was two of them.
     *
     * <p>So the rule keeps its behaviour and grows a voice. Every surface that offers a
     * flat colour asks this first and says which theme is in the way — the menus call it
     * a tile being "a picture of the past", and a tile that offers a choice it cannot
     * honour is the same failure a step earlier.
     */
    public static ChatTheme overriddenBy(Profile profile, ThemeService themes, Target target) {
        if (profile == null || themes == null) {
            return null;
        }
        ChatTheme theme = themes.live(profile, target);
        return theme == ThemeLibrary.NONE ? null : theme;
    }

    /**
     * The single colour the name above somebody's head is drawn in.
     *
     * <p><b>Above a head, a name is a scoreboard team entry.</b> The words have to be
     * the real username or the server cannot find the player, and the colour is one of
     * sixteen named ones — a protocol limit, not a Paper gap. So the per-character work
     * in {@link #name} cannot reach up there and never will.
     *
     * <p>What <em>can</em> reach up there is one colour, and it did not: the tag above
     * every head was drawn in the ladder rank's colour, so a player who bought a nick
     * colour, set it, and looked at their own head saw nothing move. That was one of the
     * three symptoms in the 2026-08-05 report and it was a real bug wearing a protocol
     * limit's clothes — "the text cannot be themed up there" is true, and it was being
     * used to excuse not colouring it at all.
     *
     * <p>Precedence is {@link #name}'s, one rung down: a live theme's colour at the
     * current phase, then the gradient's start, then the solid colour, then the fallback
     * (which callers pass as the rank colour, so an unpainted player is unchanged). The
     * prefix beside it still carries the rank, so nothing is lost by the name moving.
     */
    public static TextColor headColour(Profile profile, ThemeService themes,
                                       TextColor fallback) {
        if (profile == null || themes == null || !themes.has(profile, Perks.NICK)) {
            return fallback;
        }
        ChatTheme theme = themes.live(profile, Target.NICKTAG);
        if (theme != ThemeLibrary.NONE) {
            // Mid-sweep rather than the first stop: the first stop of most three-stop
            // palettes is the near-black end, and a name that snaps to BLACK above the
            // head reads as broken rather than as themed.
            return theme.colourAt(wrap(themes.phase() + 0.5f));
        }
        String stored = profile.state().get(CosmeticKeys.NICK_START);
        return stored == null || stored.isBlank() ? fallback : Gradients.parse(stored, fallback);
    }

    private static float wrap(float t) {
        return t - (float) Math.floor(t);
    }

    /**
     * The colour a menu should preview a name in when it has no theme.
     *
     * <p>Exists so the palette tiles and the preview head agree without either of them
     * re-deriving the fallback rules.
     */
    public static TextColor startColour(Profile profile) {
        return Gradients.parse(profile.state().get(CosmeticKeys.NICK_START), FALLBACK);
    }

    public static TextColor endColour(Profile profile) {
        return Gradients.parse(profile.state().get(CosmeticKeys.NICK_END), startColour(profile));
    }
}
