package com.thestill.cosmetic;

import com.thestill.config.Dial;
import com.thestill.config.DialRegistry;

/**
 * The five things about {@code /nick} and {@code /chatcolor} an admin can move.
 *
 * <p>Registered in one place rather than scattered across the services that read them,
 * because these five are the ones that can only be judged by standing in front of them —
 * a name length, a cooldown, how long a prompt waits, how fast a theme moves, and whether
 * chat is formatted at all. That is the same reason {@code ui.label-scale} and
 * {@code ui.label-range} are dials: the first pass set a banner to 192 blocks at 4.5x
 * and the report was "giant floating text from extremely far away in the fog".
 *
 * <p>Every numeric one declares a range, which the registry enforces at construction.
 */
public final class CosmeticDials {

    /**
     * How long a nickname may be.
     *
     * <p>Sixteen because that is a vanilla username, and a nick longer than a username
     * is a nick that pushes everybody else's line off the tab list. Capped at 32 rather
     * than left open for the same reason.
     */
    public final Dial<Integer> nickMaxLength;

    /**
     * Seconds between name changes.
     *
     * <p>Not politeness. A name is the only free text a player puts in front of
     * everybody else, and one that can change every tick is one that can be used to say
     * something and then not be the person who said it. Five minutes by default; zero is
     * allowed, and it is a decision rather than an oversight.
     */
    public final Dial<Integer> nickCooldownSeconds;

    /**
     * How long the "type your name in chat" prompt stays armed.
     *
     * <p>The source had no timeout at all: it closed the menu, added you to a set, and
     * the very next thing you typed in chat became your name — an hour later, mid
     * conversation, with the message swallowed. See {@link NickPrompt}.
     */
    public final Dial<Integer> nickPromptSeconds;

    /**
     * How far the server-wide theme phase moves per frame.
     *
     * <p>One number for every animated theme on the server, because they all share one
     * phase — see {@link ThemeService}. Too fast reads as a strobe and too slow reads as
     * a bug, and there is no way to tell which you have without watching it.
     */
    public final Dial<Double> themeSpeed;

    /**
     * Whether the plugin formats chat at all.
     *
     * <p>An off switch that hands chat straight back to vanilla, and it exists because a
     * chat renderer is the single most visible thing a plugin can get wrong: if the
     * format breaks, nobody can talk, and the fix has to be reachable without a rebuild.
     */
    public final Dial<Boolean> chatFormat;

    /**
     * Whether Shadow's 141 themes are drawn by the client's shader instead of by us.
     *
     * <p><b>Default OFF, and it must stay off.</b> {@link ThemeMarkers} works by sending
     * each glyph in a colour that encodes the theme — bytes chosen precisely because no
     * player would pick them. A player <em>with</em> the resource pack sees the real
     * thing: glyphs that physically move, screen-space sheen, a palette that keeps
     * cycling long after the packet arrived. A player <b>without</b> it sees the marker
     * colours themselves, which is a line of green-and-blue confetti.
     *
     * <p>So this is not a taste setting, it is a statement about the population: turn it
     * on only once the pack is mandatory ({@code require-resource-pack=true}) or the
     * server has some way to know who has it. Until then the server-side palette path in
     * {@link ChatTheme#render} is what everybody gets, and it needs nothing installed.
     */
    public final Dial<Boolean> clientThemes;

    /**
     * Whether {@code thestill.admin} carries every cosmetic perk.
     *
     * <p><b>On by default, and this is a deliberate hole in an otherwise strict gate.</b>
     * The owner reported that {@code /nick} and {@code /chatcolor} "don't do anything";
     * they happened to hold the top donor role so that was not the cause, but nothing in
     * the code said so — an operator on a fresh install is role {@code member}, which
     * carries no cosmetic perks at all, and every tile on both pages would have read
     * LOCKED on their own server. An owner who cannot open a menu cannot tell a locked
     * menu from a broken one, which is precisely the confusion that cost this session.
     *
     * <p>It is safe in a way a progression bypass would not be: {@link
     * com.thestill.role.Perks#ALLOWED} is an allow-list of things you could switch off
     * and still be playing the same game, so granting all of them to an admin grants no
     * advantage. It is scoped to {@link ThemeService#has} — the one funnel every cosmetic
     * check goes through — and touches {@link com.thestill.role.RoleService#has} not at
     * all, so nothing outside cosmetics inherits it.
     *
     * <p>Turn it off to see the server as a player sees it. That is the other reason it
     * is a dial and not a hard-coded {@code ||}.
     */
    public final Dial<Boolean> adminCosmetics;

    public CosmeticDials(DialRegistry registry) {
        nickMaxLength = registry.intDial("cosmetic.nick-max-length", 16)
                .category("Cosmetic").label("Nickname length")
                .describe("Longest a nickname may be. 16 is a vanilla username.")
                .range(3, 32).step(1).register();

        nickCooldownSeconds = registry.intDial("cosmetic.nick-cooldown-seconds", 300)
                .category("Cosmetic").label("Nickname cooldown")
                .describe("Seconds between name changes, so a name can be reported. "
                        + "0 disables it.")
                .range(0, 86400).step(30).register();

        nickPromptSeconds = registry.intDial("cosmetic.nick-prompt-seconds", 60)
                .category("Cosmetic").label("Prompt timeout")
                .describe("How long 'type your nickname in chat' stays armed before it "
                        + "gives up and stops eating messages.")
                .range(5, 600).step(5).register();

        themeSpeed = registry.doubleDial("cosmetic.theme-speed", 0.035)
                .category("Cosmetic").label("Theme speed")
                .describe("How far the shared animation phase advances per frame. Every "
                        + "moving theme on the server runs at this rate.")
                .range(0.001, 0.25).step(0.005).register();

        chatFormat = registry.boolDial("cosmetic.chat-format", true)
                .category("Cosmetic").label("Format chat")
                .describe("Off hands chat back to vanilla. The escape hatch if a chat "
                        + "format ever breaks — nobody can talk until it is fixed.")
                .register();

        clientThemes = registry.boolDial("cosmetic.client-themes", false)
                .category("Cosmetic").label("Shader themes")
                .describe("Draw Shadow's themes with the resource pack's text shader "
                        + "(real movement) instead of server-side colour. ONLY turn this "
                        + "on when every player has the pack — without it they see the "
                        + "raw marker colours, which is garbage.")
                .register();

        adminCosmetics = registry.boolDial("cosmetic.admin-perks", true)
                .category("Cosmetic").label("Admins own every cosmetic")
                .describe("thestill.admin carries every cosmetic perk, so an owner can "
                        + "open /nick and /chatcolor on a fresh install. Cosmetics only — "
                        + "nothing here touches progression. Off to see it as a player does.")
                .register();
    }
}
