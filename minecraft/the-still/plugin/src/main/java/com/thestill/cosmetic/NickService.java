package com.thestill.cosmetic;

import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.role.Perks;
import com.thestill.ui.Msg;
import com.thestill.ui.NametagService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * THE funnel. Every path that sets a nickname comes through {@link #apply}.
 *
 * <p><b>This class exists because of one live privilege escalation in the source.</b>
 * Over there the menu button that opens the name prompt is gated on the
 * {@code nick-custom-name} perk and says "unlocks with the Reaper rank" — but
 * {@code /nick <text>} calls {@code applyNickText}, which checks only
 * {@code canUseNick(player)}, the <em>colours</em> entitlement. So the cheaper rank
 * cannot press the button and can type the command, and the whole gate is decorative.
 * That is not a hypothetical: it is two methods with two different checks, which is what
 * happens every single time the same operation has two front doors.
 *
 * <p>So there is one door. The command opens the menu or calls this; the menu's prompt
 * calls this; anything added later calls this. The perk check, the guards, the cooldown
 * stamp, the durable save and the redraw all happen exactly once, in one place.
 */
public final class NickService {

    private final Profiles profiles;
    private final ThemeService themes;
    private final CosmeticDials dials;
    private final NametagService nametags;

    public NickService(Profiles profiles, ThemeService themes, CosmeticDials dials,
                       NametagService nametags) {
        this.profiles = profiles;
        this.themes = themes;
        this.dials = dials;
        this.nametags = nametags;
    }

    /** Can this player see {@code /nick} at all — the colours entitlement. */
    public boolean canNick(Profile profile) {
        return themes.has(profile, Perks.NICK);
    }

    /** Can this player change the words, rather than only their colour. */
    public boolean canRename(Profile profile) {
        return themes.has(profile, Perks.NICK_RENAME);
    }

    public boolean canGradient(Profile profile) {
        return themes.has(profile, Perks.NICK_GRADIENT);
    }

    /**
     * Sets a nickname, or explains why not.
     *
     * @return true if the name changed
     */
    public boolean apply(Player player, Profile profile, String raw) {
        if (!canNick(profile)) {
            Msg.error(player, "Recolouring your name is a rank perk. See /store.");
            return false;
        }
        if (!canRename(profile)) {
            // The exact gate the source's command walked straight past.
            Msg.error(player, "Your rank recolours your name; it does not rename it.");
            Msg.info(player, "Changing the words is a higher tier — see /store.");
            return false;
        }

        NickText.Result result = NickText.check(raw, rulesFor(player), System.currentTimeMillis(),
                profile.state().get(CosmeticKeys.NICK_SET_AT));
        if (!result.ok()) {
            Msg.error(player, result.message());
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.2f);
            return false;
        }

        profile.state().set(CosmeticKeys.NICK_TEXT, result.text());
        profile.state().set(CosmeticKeys.NICK_SET_AT, System.currentTimeMillis());
        profile.markDirty();
        // Durable rather than write-behind: a name is the thing a player will file a
        // ticket about, and it is exactly the case Phase 0's rule 4 draws the line for.
        profiles.saveDurable(profile);
        redraw(player, profile);

        player.sendMessage(Component.text("  You are ", NamedTextColor.DARK_GRAY)
                .append(NickRender.name(profile, player.getName(), themes))
                .append(Component.text(" now.", NamedTextColor.DARK_GRAY)));
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.4f);
        return true;
    }

    /**
     * Back to the username.
     *
     * <p>Deliberately not on the cooldown and deliberately not stamping it either.
     * Clearing is the one change that cannot be used to dodge anything — it puts your
     * real name back — and making somebody wait five minutes to undo a typo is a support
     * ticket for no benefit.
     */
    public void clear(Player player, Profile profile) {
        profile.state().set(CosmeticKeys.NICK_TEXT, "");
        profile.markDirty();
        profiles.saveDurable(profile);
        redraw(player, profile);
        Msg.info(player, "Name cleared. You are " + player.getName() + " again.");
    }

    /**
     * Tab, the board and the tag above the head, immediately.
     *
     * <p>One call does all three: {@link NametagService#refresh} rewrites the tab entry
     * and rebuilds the scoreboard team on every viewer's board, and the sidebar composes
     * its identity row through {@link NametagService#fullName} on its own ten-tick
     * ticker. <b>A cosmetic change has to be visible without a relog</b> — a write with
     * no redraw looks exactly like a write that never happened, and that is half of what
     * was reported on 2026-08-05.
     */
    public void redraw(Player player, Profile profile) {
        if (nametags != null) {
            nametags.refresh(player);
        }
    }

    /**
     * "Saved — but you will not see it, and here is why."
     *
     * <p>The precedence (theme &gt; gradient &gt; solid) is deliberate and stays. What
     * could not stay is it being silent: a player with a theme switched on clicked every
     * swatch on both pages, every click saved, and nothing anywhere changed. Told once,
     * at the moment of the click, it stops being a broken menu and becomes a setting they
     * know how to undo.
     *
     * @return true if something is in the way, for callers that want to draw it too
     */
    public boolean warnIfOverridden(Player player, Profile profile, Target target) {
        var over = NickRender.overriddenBy(profile, themes, target);
        if (over == null) {
            return false;
        }
        player.sendMessage(Component.text("  Saved — but the ", NamedTextColor.DARK_GRAY)
                .append(over.render(over.name(), themes.phase(), false))
                .append(Component.text(" theme is on " + target.where().toLowerCase(Locale.ROOT)
                        + ", and a theme covers a colour.", NamedTextColor.DARK_GRAY)));
        Msg.info(player, "Turn the theme off to see this colour.");
        return true;
    }

    /**
     * The whole line — role, ladder rung and name — exactly as tab and chat draw it.
     *
     * <p>The nickname menu's preview head shows a "Full line:" ({@code NickMenu.java:229})
     * and the chat menu's paper shows one message ({@code ChatColorMenu.java:337}). Over
     * there the second of those is the literal string {@code "Shadow [P1] [A] You: "},
     * hard-coded, and it has not matched anybody's real line for months. A preview built
     * from its own code path is a preview that eventually lies, so both go through
     * {@link NametagService#identity} — the same call the chat renderer makes.
     */
    public Component fullLine(Player player, Profile profile) {
        Component name = NickRender.name(profile, player.getName(), themes);
        return nametags == null ? name : nametags.identity(profile).append(name);
    }

    /** What the "Max N characters" line on the rename button says. */
    public int maxNameLength() {
        return dials.nickMaxLength.asInt();
    }

    /**
     * "You cannot do that", said once, the same way everywhere.
     *
     * <p>The source's locked tiles carry a {@code "noop"} action and are therefore
     * <em>silent</em> when clicked, which reads as a broken button rather than as a locked
     * one. Every menu here says why instead.
     */
    public void deny(Player player, String why) {
        player.sendMessage(Component.text("  " + why, NamedTextColor.DARK_GRAY,
                net.kyori.adventure.text.format.TextDecoration.ITALIC));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.2f);
    }

    /**
     * The world, as four facts.
     *
     * <p><b>Both predicates look at online players only</b>, and that is a decision. The
     * complete answer — "is this the username of any account that has ever existed" —
     * needs a Mojang lookup, which is a network call on whatever thread asked, and the
     * name-change flow runs on the main thread. The impersonation that actually does
     * damage is of somebody who is in the room; the rest is a moderator's job, and this
     * is the class that made that reachable by having a slur filter at all.
     */
    private NickText.Rules rulesFor(Player asker) {
        String self = asker.getName().toLowerCase(Locale.ROOT);
        return new NickText.Rules(
                dials.nickMaxLength.asInt(),
                dials.nickCooldownSeconds.asInt() * 1000L,
                candidate -> {
                    String folded = candidate.toLowerCase(Locale.ROOT);
                    if (folded.equals(self)) {
                        return false;   // your own username is always yours to wear
                    }
                    for (Player other : Bukkit.getOnlinePlayers()) {
                        if (other.getName().toLowerCase(Locale.ROOT).equals(folded)) {
                            return true;
                        }
                    }
                    return false;
                },
                candidate -> {
                    String folded = candidate.toLowerCase(Locale.ROOT);
                    for (Player other : Bukkit.getOnlinePlayers()) {
                        if (other.getUniqueId().equals(asker.getUniqueId())) {
                            continue;
                        }
                        Profile theirs = profiles.get(other.getUniqueId());
                        if (theirs == null) {
                            continue;
                        }
                        String worn = NickRender.textOf(theirs, other.getName(), themes);
                        if (worn.toLowerCase(Locale.ROOT).equals(folded)) {
                            return true;
                        }
                    }
                    return false;
                });
    }
}
