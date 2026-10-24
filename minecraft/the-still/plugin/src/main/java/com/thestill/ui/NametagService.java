package com.thestill.ui;

import com.thestill.core.Service;
import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.rank.Rank;
import com.thestill.rank.RankService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rank prefixes above the head and in the tab list.
 *
 * <h2>Why teams, and why the name encodes the rank</h2>
 * An above-head prefix can only come from a scoreboard {@link Team}. Tab sorts
 * players alphabetically <em>by team name</em>, so putting a zero-padded rank
 * index in the name is what makes the tab list ordered by rank rather than by
 * whatever order people happened to log in. Shadow Prison learned this the same
 * way — its teams were named by entity id and tab order was effectively random.
 *
 * <h2>Why there is no periodic sync</h2>
 * Every player has their own scoreboard (the sidebar needs that), so a team has
 * to exist on all of them — which makes a full sync O(players squared). Shadow
 * Prison ran exactly that on <em>every sell</em>. Here a sync happens only when
 * something actually changed: a join, or a rank up.
 */
public final class NametagService implements Service {

    private static final TextColor TAB_HEADER_START = TextColor.color(0x4E7C82);
    private static final TextColor TAB_HEADER_END = TextColor.color(0xC9D6D8);

    private final Logger log;
    private final Plugin plugin;
    private final Profiles profiles;
    private final RankService ranks;
    private org.bukkit.scheduler.BukkitTask ticker;

    private final com.thestill.role.RoleService roles;

    /**
     * Late-wired, and nullable on purpose.
     *
     * <p>The cosmetics need the role table, which needs the profile store, which needs
     * this service's own ticker to exist before anything can animate. Rather than
     * reorder the composition root around a cosmetic, this stays null until
     * {@link #withThemes} is called, and every path through {@code NickRender} already
     * treats a null theme service as "no themes" — which is also exactly what a server
     * running with the cosmetics disabled should look like.
     */
    private com.thestill.cosmetic.ThemeService themes;

    public void withThemes(com.thestill.cosmetic.ThemeService themes) {
        this.themes = themes;
    }

    public NametagService(Logger log, Plugin plugin, Profiles profiles, RankService ranks,
                          com.thestill.role.RoleService roles) {
        this.roles = roles;
        this.log = log;
        this.plugin = plugin;
        this.profiles = profiles;
        this.ranks = ranks;
    }

    @Override
    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                player.playerListName(null);
            } catch (Exception ignored) {
                // Shutting down; a cosmetic reset failing must not stop anything.
            }
        }
    }

    /**
     * Brings a joining player fully into the display: their tag onto everyone
     * else's board, and everyone else's onto theirs.
     *
     * <p>Delayed a few ticks. The sidebar assigns the player their own scoreboard
     * on join, and writing teams onto the board they are about to stop using
     * accomplishes nothing.
     */
    public void onJoin(Player joiner) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!joiner.isOnline()) {
                return;
            }
            for (Player other : Bukkit.getOnlinePlayers()) {
                applyTo(other.getScoreboard(), joiner);
                applyTo(joiner.getScoreboard(), other);
            }
            refreshTabName(joiner);
            sendTabHeader(joiner);
        }, 10L);
    }

    /** Called when a player's rank changes — the only other time anything moves. */
    public void refresh(Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            applyTo(viewer.getScoreboard(), player);
        }
        refreshTabName(player);
    }

    // ---- above the head --------------------------------------------------

    private void applyTo(Scoreboard board, Player target) {
        Profile profile = profiles.get(target.getUniqueId());
        if (profile == null) {
            return;
        }
        Rank rank = ranks.rankOf(profile);
        String teamName = teamNameFor(rank, target);

        try {
            // Drop any team this player used to be in — a rank up changes the
            // team NAME, so the old one would otherwise linger and keep them
            // sorted at their previous rank forever.
            for (Team existing : board.getTeams()) {
                if (existing.getName().startsWith("st_")
                        && existing.getName().endsWith("_" + target.getEntityId())
                        && !existing.getName().equals(teamName)) {
                    existing.unregister();
                }
            }

            Team team = board.getTeam(teamName);
            if (team == null) {
                team = board.registerNewTeam(teamName);
            }
            if (!team.hasEntry(target.getName())) {
                team.addEntry(target.getName());
            }
            team.prefix(Component.text("[", NamedTextColor.DARK_GRAY)
                    .append(Component.text(rank.display(), rank.colour())
                            .decoration(TextDecoration.BOLD, true))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY)));
            // A team's colour is a NamedTextColor and nothing else, so whatever hex we
            // land on has to be snapped to the nearest of sixteen.
            //
            // It is the NICK's colour, not the rank's. The rank is already beside it in
            // the prefix, and drawing the name in the rank colour too meant a player who
            // bought a nick colour and looked at their own head saw nothing happen —
            // one of the three symptoms reported on 2026-08-05. See NickRender.headColour
            // for why the words themselves cannot follow.
            team.color(Gradients.nearestNamed(
                    com.thestill.cosmetic.NickRender.headColour(profile, themes,
                            rank.colour())));
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        } catch (Exception e) {
            log.log(Level.WARNING, "could not apply nametag for " + target.getName(), e);
        }
    }

    /**
     * {@code st_<sortKey>_<entityId>}.
     *
     * <p>The sort key is inverted — highest rank gets the lowest number — because
     * tab sorts ascending and the top of the ladder belongs at the top of the
     * list. Zero-padded so "10" does not sort before "9".
     */
    private String teamNameFor(Rank rank, Player target) {
        int sortKey = Math.clamp(99 - rank.index(), 0, 99);
        return String.format("st_%02d_%d", sortKey, target.getEntityId());
    }

    // ---- tab list --------------------------------------------------------

    private void refreshTabName(Player player) {
        Profile profile = profiles.get(player.getUniqueId());
        if (profile == null) {
            return;
        }
        player.playerListName(fullName(profile, player.getName()));
    }

    /**
     * Tags plus the name, exactly as every surface draws a player.
     *
     * <p><b>There is one of these because there were two.</b> Tab called
     * {@code identity().append(NickRender.name(...))} and the sidebar's identity row
     * composed the same line by hand with {@code player.getName()} in flat white — so a
     * nickname, a nick colour and a theme all showed up in tab and in chat and never once
     * on the board. That was the "doesn't change the scoreboard" half of the 2026-08-05
     * report, and it is the exact failure this class's own header warns about: a second
     * copy of one composition drifts, and the drift is invisible until somebody looks at
     * both at once.
     *
     * <p>{@code /still cosmetic <who>} prints this, so the instrument cannot claim a
     * line the surfaces do not draw.
     */
    public Component fullName(Profile profile, String username) {
        return identity(profile)
                .append(com.thestill.cosmetic.NickRender.name(profile, username, themes));
    }

    /**
     * The tags before somebody's name, in the one order this server uses.
     *
     * <p><b>Role first, then ladder.</b> Two axes, and they read left to right in the
     * order they were earned in: what you bought, then how deep you have got. Every
     * surface that renders a player goes through here — tab, above the head, the
     * board — so they cannot drift apart, which is the failure the source server
     * hit with eight near-copies of one gradient helper.
     */
    public Component identity(Profile profile) {
        Rank rank = ranks.rankOf(profile);
        Component out = roles == null ? Component.empty() : roles.prefix(profile);
        return out.append(Component.text("[", NamedTextColor.DARK_GRAY))
                .append(Component.text(rank.display(), rank.colour())
                        .decoration(TextDecoration.BOLD, true))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY));
    }

    /**
      * The tab header and footer.
      *
      * <p>It was a flat two-stop gradient and an online count, which is a placeholder
      * with good manners. The header now burns in the same ember sweep as the sidebar
      * and the welcome banners; the footer carries the line the server is actually
      * about, plus where to buy things, because tab is the one screen every player
      * opens without being told to.
      */
     private void sendTabHeader(Player player) {
         tabPhase += 0.02f;
         if (tabPhase > 1f) {
             tabPhase -= 1f;
         }
         Component header = Component.empty()
                 .append(Component.newline())
                 .append(Gradients.sweep("  ❖ THE STILL ❖  ",
                         com.thestill.ui.sidebar.StillLines.EMBER,
                         com.thestill.ui.sidebar.StillLines.EMBER_PALE, 1.3f, tabPhase, true))
                 .append(Component.newline())
                 .append(Component.text("  noise calls  ·  heat keeps  ",
                         NamedTextColor.DARK_GRAY, TextDecoration.ITALIC))
                 .append(Component.newline());

         Component footer = Component.newline()
                 .append(Component.text("Online ", NamedTextColor.DARK_GRAY))
                 .append(Component.text(Bukkit.getOnlinePlayers().size(),
                         NamedTextColor.WHITE, TextDecoration.BOLD))
                 .append(Component.text("   │   ", NamedTextColor.DARK_GRAY))
                 .append(Component.text("Depth ", NamedTextColor.DARK_GRAY))
                 .append(Component.text(deepestReached(player), NamedTextColor.AQUA,
                         TextDecoration.BOLD))
                 .append(Component.newline());

         player.sendPlayerListHeaderAndFooter(header, footer);
     }

     /** Advanced with every header send. */
     private float tabPhase;

     /** How far down this player has actually been, for the tab footer. */
     private String deepestReached(Player player) {
         Profile profile = profiles.get(player.getUniqueId());
         if (profile == null) {
             return "-";
         }
         return "D" + Math.max(1, ranks.rankOf(profile).index() + 1);
     }

    @Override
    public void start() {
        // Three ticks a frame. Slow enough to be free, fast enough that a sweeping tag
        // reads as motion rather than as a stutter — and it doubles as the tab header's
        // own animation, which was previously only redrawn when somebody joined.
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshTabHeaders, 60L, 3L);
    }

    /** Re-sends the header to everyone; cheap, and the online count moves. */
    public void refreshTabHeaders() {
        Bukkit.getOnlinePlayers().forEach(this::sendTabHeader);
        animateTags();
    }

    /**
     * Redraws the tags that move.
     *
     * <p>Only for players whose role actually carries {@code TAG_ANIMATED} — everybody
     * else is untouched, so the cost is one team-prefix write per top-tier donor per
     * frame and zero for the rest of the server. A ticker that redrew every nametag
     * would be sending a packet per player per player per frame, which is how a
     * cosmetic ends up on a timings report.
     */
    private void animateTags() {
        if (roles == null) {
            return;
        }
        roles.advance();
        // The one place the theme phase moves. A second ticker would walk the same
        // players on a different beat and the two clocks would visibly drift apart
        // within a minute — see ThemeService.
        if (themes != null) {
            themes.advance();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            Profile profile = profiles.get(player.getUniqueId());
            if (profile == null) {
                continue;
            }
            if (roles.hasAnimatedTag(profile)
                    || (themes != null && themes.isAnimated(profile))) {
                refresh(player);
            }
        }
    }
}
