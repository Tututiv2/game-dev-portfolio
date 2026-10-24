package com.thestill.cosmetic;

import com.thestill.core.Service;
import com.thestill.player.Profile;
import com.thestill.role.RoleService;

/**
 * How far through the sweep every animated theme on the server is.
 *
 * <p><b>One phase, server-wide, and no ticker of its own.</b> Both halves of that
 * matter.
 *
 * <p>One phase because two players standing next to each other with themes out of step
 * look like a bug, and in step they look like a feature. That is
 * {@link RoleService#advance()}'s reasoning and it applies identically here — this is
 * literally the same decision a second time, which is why the two are advanced from the
 * same place.
 *
 * <p>No ticker because {@link com.thestill.ui.NametagService} already runs one at three
 * ticks a frame, already walks the online players, and already knows which of them carry
 * something that moves. A second timer would double that walk to redraw the same players
 * on a different beat — visibly, since the two clocks would drift apart within a minute.
 * So this exposes {@link #advance()} and the nametag ticker calls it, exactly as it
 * already calls {@code roles.advance()}.
 *
 * <p>The field is {@code volatile} because chat renders on the async chat thread while
 * the phase is written on the main one.
 */
public final class ThemeService implements Service {

    private final CosmeticDials dials;
    private final RoleService roles;

    private volatile float phase;

    public ThemeService(CosmeticDials dials, RoleService roles) {
        this.dials = dials;
        this.roles = roles;
    }

    /** Advanced by whoever is driving the refresh — the nametag ticker. */
    public void advance() {
        float next = phase + (float) dials.themeSpeed.asDouble();
        if (next > 1f) {
            next -= 1f;
        }
        phase = next;
        redrawPickers();
    }

    // ---- the pickers, redrawn ----------------------------------------------

    /**
     * The cosmetic menu each player currently has open, if it shows a theme.
     *
     * <p><b>A tile is a picture taken when the menu was built.</b> That is already this
     * codebase's phrase for why a click re-checks its perk — and it is just as true of
     * what the tile <em>looks like</em>. Every theme preview was rendered once, at the
     * phase the menu happened to open on, and then sat there: twenty-seven tiles all
     * claiming to show what a moving theme does, none of them moving. The report called
     * them "broken", and from the other side of the screen a preview that does not
     * preview is exactly that.
     *
     * <p>Keyed by viewer, so a player can only ever hold one, and holding a reference to
     * a closed menu is impossible for longer than one frame — {@link #redrawPickers}
     * drops any entry whose viewer is no longer looking at it.
     */
    private final java.util.Map<java.util.UUID, com.thestill.ui.Menu> pickers =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Called from a picker's own {@code build()}; idempotent, so a redraw re-arms it. */
    public void watchPicker(org.bukkit.entity.Player viewer, com.thestill.ui.Menu menu) {
        pickers.put(viewer.getUniqueId(), menu);
    }

    /**
     * Redraws every open picker, one frame behind the phase it is drawing.
     *
     * <p><b>No ticker of its own</b>, for the same reason the phase has none: this rides
     * the nametag service's existing three-tick frame, so a tile and the tag above the
     * head that it is previewing move on the same clock rather than drifting apart —
     * which would look like two different themes.
     *
     * <p>Every other frame, not every frame. A full rebuild is fifty-four item stacks and
     * a packet; at three ticks that is seven a second per open menu, for an animation
     * nobody can see at that rate anyway.
     */
    private void redrawPickers() {
        if (pickers.isEmpty() || (frame++ & 1) == 1) {
            return;
        }
        pickers.entrySet().removeIf(entry -> {
            org.bukkit.entity.Player viewer = org.bukkit.Bukkit.getPlayer(entry.getKey());
            if (viewer == null
                    || viewer.getOpenInventory().getTopInventory().getHolder()
                            != entry.getValue()) {
                return true;
            }
            try {
                entry.getValue().refresh();
            } catch (Exception e) {
                // A menu that throws while redrawing must not take the nametag ticker —
                // and therefore every animated tag on the server — down with it.
                return true;
            }
            return false;
        });
    }

    private int frame;

    public float phase() {
        return phase;
    }

    // ---- what somebody is actually wearing --------------------------------

    /**
     * The theme a surface is set to, before any question of whether it is allowed.
     *
     * <p>{@link Target#NICKTAG} may be borrowing chat's — that is
     * {@link CosmeticKeys#TAG_MATCH_CHAT}, and resolving it here rather than at each
     * call site is what stops the tab list and the chat prefix disagreeing about which
     * theme somebody has on.
     */
    public ChatTheme chosen(Profile profile, Target target) {
        if (profile == null) {
            return ThemeLibrary.NONE;
        }
        if (target == Target.NICKTAG && profile.state().get(CosmeticKeys.TAG_MATCH_CHAT)) {
            return ThemeLibrary.byId(profile.state().get(Target.CHAT.themeKey()));
        }
        return ThemeLibrary.byId(profile.state().get(target.themeKey()));
    }

    /**
     * The theme that will actually render — {@link ThemeLibrary#NONE} if anything is off
     * or unpaid for.
     *
     * <p>This is Law 2 in its second home. The stored flag says what the player wants;
     * the perk says what they may have; and a theme that was unlocked when it was picked
     * has to stop rendering the moment the rank behind it lapses, without anybody
     * running a cleanup job over the profile table.
     */
    public ChatTheme live(Profile profile, Target target) {
        if (profile == null || !profile.state().get(target.enabledKey())) {
            return ThemeLibrary.NONE;
        }
        ChatTheme theme = chosen(profile, target);
        if (theme == ThemeLibrary.NONE) {
            return ThemeLibrary.NONE;
        }
        return unlocked(profile, theme) ? theme : ThemeLibrary.NONE;
    }

    /** Does this player's live role set open this theme? */
    public boolean unlocked(Profile profile, ChatTheme theme) {
        // Through has(), not straight to roles.has(): the admin bypass below is the whole
        // reason there is one funnel, and a predicate that reached past it would let an
        // operator open the picker and then be refused by the tile inside it.
        return theme.unlockedBy(perk -> has(profile, perk));
    }

    /**
     * <b>THE</b> cosmetic entitlement check. Every menu, every renderer, every command.
     *
     * <p>Two ways to hold a perk, and the second is deliberate: an operator holds all of
     * them. See {@link CosmeticDials#adminCosmetics} for why that is safe here and would
     * not be anywhere else — and note it is scoped to this method rather than added to
     * {@link com.thestill.role.RoleService#has}, so only cosmetics inherit it.
     */
    public boolean has(Profile profile, String perk) {
        if (roles != null && roles.has(profile, perk)) {
            return true;
        }
        return adminHoldsEverything(profile);
    }

    /**
     * Is this profile an online operator, with the bypass switched on?
     *
     * <p>Asked of the {@link org.bukkit.entity.Player} rather than of anything stored,
     * because a permission is live and a profile flag would be a second copy of it. An
     * offline profile answers no, which is right: nothing renders for somebody who is not
     * here.
     *
     * <p><b>This is reached from the async chat thread</b>, via {@code ChatListener} →
     * {@link NickRender#chat}. Both calls are map reads — Paper keys online players by
     * UUID, and a permissible is a hash lookup — so the worst a race with a join-time
     * {@code applyPermissions} can produce is one line of chat drawn from a
     * one-tick-stale answer about an operator's own cosmetics. Nothing here writes.
     */
    private boolean adminHoldsEverything(Profile profile) {
        if (profile == null || dials == null || !dials.adminCosmetics.asBoolean()) {
            return false;
        }
        if (org.bukkit.Bukkit.getServer() == null) {
            // Under unit test there is no server to ask, and the answer that keeps the
            // tests testing the RULE rather than the bypass is "no". Same degradation
            // OreTable makes when Paper's registry does not exist.
            return false;
        }
        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(profile.uuid());
        return player != null
                && player.hasPermission(com.thestill.command.StillCommand.PERMISSION);
    }

    // ---- drawing it ---------------------------------------------------------

    /**
     * <b>The</b> way a theme becomes text. Two paths, one switch.
     *
     * <p>{@link CosmeticDials#clientThemes} picks between them, and they are not the same
     * product. The server-side path ({@link ChatTheme#render}) walks the palette here and
     * sends a colour per glyph, so it works for everybody and reaches nobody's GPU —
     * which means a MOTION theme reads as its colours moving rather than as its geometry.
     * The client path ({@link ThemeMarkers#encode}) sends marker bytes the pack's
     * {@code rendertype_text.vsh} decodes, and the glyphs actually move, sweep and
     * strobe. It also costs one packet total instead of one per frame, because the shader
     * adds {@code GameTime} to the phase itself.
     *
     * <p>The trade is who it works for. A player without the pack gets the marker colours
     * raw. That is why the dial defaults off and why this method exists at all rather
     * than each call site deciding — a surface that forgot to ask would be the one
     * surface showing somebody confetti.
     *
     * <p>Every SHADOW theme goes through the shader when it is on, not only the MOTION
     * ones: the pack's palette ladder covers all 141 indices, and a COLOUR theme routed
     * server-side while a MOTION theme is routed client-side would animate on two
     * different clocks in the same chat line. {@link ThemeMarkers#supports} is what keeps
     * The Still's own themes out of it — see there for why they must never go near it.
     *
     * <p>The <b>pickers do not call this</b>, on purpose. {@link ChatTheme#preview} stays
     * server-side so a tile in {@code ThemeGridMenu} is legible to a player who has not
     * loaded the pack yet — a chooser you cannot read is a chooser nobody buys from, and
     * it is the one screen where being wrong costs a sale rather than a message.
     */
    public net.kyori.adventure.text.Component render(ChatTheme theme, String text,
                                                     boolean bold) {
        if (theme == null) {
            return net.kyori.adventure.text.Component.empty();
        }
        if (dials != null && dials.clientThemes.asBoolean() && ThemeMarkers.supports(theme)) {
            return ThemeMarkers.encode(text, theme, bold);
        }
        return theme.render(text, phase(), bold);
    }

    /** Does anything about this player need redrawing every frame? */
    public boolean isAnimated(Profile profile) {
        if (dials != null && dials.clientThemes.asBoolean()) {
            // The shader re-derives the phase from GameTime every frame on its own, so
            // resending the same marker bytes three times a second buys nothing but
            // packets. This is the one real saving the client path makes.
            return false;
        }
        return live(profile, Target.NICKTAG).isMotion();
    }
}
