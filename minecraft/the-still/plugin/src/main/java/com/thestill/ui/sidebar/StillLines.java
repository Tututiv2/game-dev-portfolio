package com.thestill.ui.sidebar;

import com.thestill.economy.Money;
import com.thestill.economy.SellService;
import com.thestill.mine.MineService;
import com.thestill.mine.SessionStats;
import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.rank.Rank;
import com.thestill.rank.RankService;
import com.thestill.region.Region;
import com.thestill.region.RegionService;
import com.thestill.region.RegionType;
import net.kyori.adventure.text.Component;
import com.thestill.config.Dial;
import com.thestill.config.DialRegistry;
import com.thestill.ui.Gradients;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * What the sidebar says in Phase 1.
 *
 * <p>This class is the Phase 1 gate. "A brand-new player reaches D5 without
 * asking a single question" is not won by a tutorial — it is won by the next
 * action being visible at all times. Hence the bottom line, which is not a
 * statistic at all: it is an instruction that changes as the player's situation
 * does.
 *
 * <p>Priority 50 is left empty on purpose. That is where the Din bar goes in
 * Phase 2, and leaving the gap now means adding it costs one call and moves
 * nothing.
 */
public final class StillLines {

    private final Profiles profiles;
    private final RankService ranks;
    private final RegionService regions;
    private final MineService mines;
    private final SessionStats sessions;
    private final SellService selling;
    /**
     * Where the store is.
     *
     * <p>Passed in rather than registered here, because {@code /store} needs the same
     * value and <b>a dial may only be registered once</b> — the registry throws on a
     * duplicate key, which is Phase 0's rule doing exactly its job. One owner, two
     * readers.
     */
    private final Dial<String> storeUrl;
    private final com.thestill.role.RoleService roles;

    /**
     * The one composer for "tags plus name".
     *
     * <p>Not optional and not late-wired: the board used to build that line itself and
     * therefore never showed a nickname, a nick colour or a theme. See
     * {@link com.thestill.ui.NametagService#fullName}.
     */
    private final com.thestill.ui.NametagService nametags;

    public StillLines(Profiles profiles, RankService ranks, RegionService regions,
                      MineService mines, SessionStats sessions, SellService selling,
                      DialRegistry dials, com.thestill.role.RoleService roles,
                      Dial<String> storeUrl, com.thestill.ui.NametagService nametags) {
        this.nametags = nametags;
        this.roles = roles;
        this.storeUrl = storeUrl;
        this.profiles = profiles;
        this.ranks = ranks;
        this.regions = regions;
        this.mines = mines;
        this.sessions = sessions;
        this.selling = selling;
    }

    public void registerAll(Sidebar sidebar) {
        // WHO you are, first. The board opened on a bare "Rank X" line with no name
        // anywhere on it, which is the single biggest reason it read as a debug
        // readout rather than as a server's board.
        sidebar.register("identity", 98, this::identityLine);
        sidebar.register("gap-identity", 92, SidebarLine.spacer());

        sidebar.register("rank", 72, this::rankLine);
        sidebar.register("balance", 70, this::balanceLine);
        sidebar.register("next-rank", 68, this::nextRankLine);
        sidebar.register("gap-money", 60, SidebarLine.spacer());

        // 50 — reserved for Din (Phase 2), then heat (Phase 4).

        sidebar.register("depth", 30, this::depthLine);
        sidebar.register("shaft", 28, this::shaftLine);
        sidebar.register("session", 26, this::sessionLine);
        sidebar.register("gap-place", 20, SidebarLine.spacer());

        sidebar.register("online", 14, this::onlineLine);
        sidebar.register("hint", 8, this::hintLine);
        sidebar.register("store", 2, this::storeLine);
    }

    // ---- the look -------------------------------------------------------

    /**
     * The coloured edge bar every line starts with.
     *
     * <p>Ported from Shadow Prison's {@code PrisonScoreboard}, and it does more work
     * than one character has any right to. It gives every row a common left margin so
     * the labels line up, and it carries the row's colour <em>outside</em> the words,
     * so a section reads as a group without the text itself being tinted. A board of
     * plain "Label value" lines has neither, which is what made this one look like a
     * readout rather than a server.
     */
    private static Component bar(TextColor colour) {
        return Component.text("▎ ", colour);
    }

    /** The shape of every row on the board. */
    private static Component row(TextColor barColour, String label, Component value) {
        return bar(barColour)
                .append(Component.text(label + ": ", NamedTextColor.WHITE))
                .append(value);
    }

    private static Component row(TextColor barColour, String label,
                                 String value, TextColor valueColour) {
        return row(barColour, label, Component.text(value, valueColour));
    }

    // ---- lines ----------------------------------------------------------

    /** The player, badged exactly as they appear in tab and over their own head. */
    private Component identityLine(Player viewer) {
        Profile profile = profiles.get(viewer.getUniqueId());
        if (profile == null) {
            return null;
        }
        // Literally the same composer as the tab list and chat — not a copy of its
        // shape. The copy is what this comment used to claim and it was false: it
        // rebuilt the prefixes by hand and then finished with a flat white
        // player.getName(), so no nickname, nick colour or theme ever reached the board.
        return bar(NamedTextColor.WHITE).append(nametags.fullName(profile, viewer.getName()));
    }

    private Component rankLine(Player viewer) {
        Profile profile = profiles.get(viewer.getUniqueId());
        if (profile == null) {
            return null;
        }
        Rank rank = ranks.rankOf(profile);
        return row(EMBER, "Rank", Component.text(rank.display(), rank.colour())
                .decoration(TextDecoration.BOLD, true));
    }

    private Component onlineLine(Player viewer) {
        return row(NamedTextColor.GREEN, "Online",
                String.valueOf(Bukkit.getOnlinePlayers().size()), NamedTextColor.WHITE);
    }

    /**
     * The store address, bare, at the very bottom.
     *
     * <p>No label and no protocol. A line reading {@code Store: https://...} spends a
     * third of the board's width on two words nobody needs to read.
     */
    private Component storeLine(Player viewer) {
        String address = storeUrl.get().trim()
                .replaceFirst("^https?://", "").replaceFirst("/+$", "");
        return address.isEmpty() ? null
                : Component.text("  " + address, NamedTextColor.YELLOW);
    }

    private Component balanceLine(Player viewer) {
        Profile profile = profiles.get(viewer.getUniqueId());
        if (profile == null) {
            return null;
        }
        return row(NamedTextColor.GOLD, "Balance",
                Money.compact(profile.balance()), NamedTextColor.YELLOW);
    }

    private Component nextRankLine(Player viewer) {
        Profile profile = profiles.get(viewer.getUniqueId());
        if (profile == null) {
            return null;
        }
        Rank next = ranks.nextRank(profile);
        if (next == null) {
            return row(NamedTextColor.GOLD, "Next", "max rank", NamedTextColor.GOLD);
        }
        long cost = ranks.costOf(next);
        boolean affordable = profile.balance() >= cost;
        // A percentage as well as a price. "How close am I" is the question a player
        // actually has, and subtracting two numbers is not their job.
        int percent = (int) Math.min(100, profile.balance() * 100 / Math.max(1, cost));
        return row(NamedTextColor.GOLD, "Next",
                Component.text(next.display(), next.colour())
                        .append(Component.text(" " + Money.compact(cost),
                                affordable ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY))
                        .append(Component.text(" (" + percent + "%)",
                                affordable ? NamedTextColor.GREEN : NamedTextColor.GRAY)));
    }

    private Component depthLine(Player viewer) {
        Region region = regions.regionFor(viewer);
        if (region == null) {
            return row(NamedTextColor.AQUA, "Where", "the rigs", NamedTextColor.WHITE);
        }
        Region depth = depthOf(region);
        if (depth == null) {
            return row(NamedTextColor.AQUA, "Where", region.id(), NamedTextColor.WHITE);
        }
        int number = depth.state().get(RankService.DEPTH_NUMBER);
        return row(NamedTextColor.AQUA, "Depth", "D" + number, NamedTextColor.WHITE);
    }

    /** Hidden entirely when not in a mine, rather than showing a meaningless zero. */
    private Component shaftLine(Player viewer) {
        Region region = regions.regionFor(viewer);
        // The nearest region that actually holds ore, NOT the depth. On a plains
        // the ore is a nested pit, so the depth itself has no table and asking it
        // would blank this line for exactly the six depths that are landmarks.
        Region mine = region == null ? null : mineOf(region);
        if (mine == null) {
            return null;
        }
        if (mines.isResetting(mine.id())) {
            int percent = (int) Math.round(mines.resetProgress(mine.id()) * 100);
            return row(NamedTextColor.AQUA, "Refilling", percent + "%", NamedTextColor.AQUA);
        }
        int remaining = (int) Math.round((1.0 - mines.minedFraction(mine)) * 100);
        NamedTextColor colour = remaining > 60 ? NamedTextColor.WHITE
                : remaining > 30 ? NamedTextColor.YELLOW : NamedTextColor.GOLD;
        return row(NamedTextColor.AQUA, "Ore left", remaining + "%", colour);
    }

    private Component sessionLine(Player viewer) {
        long blocks = sessions.blocksThisSession(viewer.getUniqueId());
        if (blocks == 0) {
            return null;
        }
        return row(NamedTextColor.AQUA, "Mined", String.valueOf(blocks), NamedTextColor.WHITE);
    }

    /**
     * The line that does the actual teaching.
     *
     * <p>Not a statistic — the single next action, recomputed every refresh. A
     * player who never reads chat still cannot get stuck, because the answer is
     * on screen and it changes when their situation does.
     */
    private Component hintLine(Player viewer) {
        Profile profile = profiles.get(viewer.getUniqueId());
        if (profile == null) {
            return null;
        }
        Rank next = ranks.nextRank(profile);
        if (next != null && profile.balance() >= ranks.costOf(next)) {
            return Component.text("  » /rankup", NamedTextColor.GREEN, TextDecoration.BOLD);
        }
        if (selling.appraise(viewer) > 0) {
            return Component.text("  » /sell", NamedTextColor.AQUA);
        }
        Region region = regions.regionFor(viewer);
        if (region == null || depthOf(region) == null) {
            // Above the fog, or between depths. There is no command for this and
            // there never will be — the only way on is down.
            return Component.text("  » descend", NamedTextColor.DARK_AQUA);
        }
        return Component.text("  » mine ore", NamedTextColor.DARK_GRAY);
    }

    // ---- helpers --------------------------------------------------------

    /** Walks up to the DEPTH, so standing in a pit still reports its level. */
    private Region depthOf(Region region) {
        for (Region link : regions.index().chainOf(region)) {
            if (link.type() == RegionType.DEPTH) {
                return link;
            }
        }
        return null;
    }

    /**
     * The nearest region in the chain that has an ore table.
     *
     * <p>Deepest-first, so a plains quarry answers with the pit rather than the
     * plain it sits in.
     */
    private Region mineOf(Region region) {
        for (Region link : regions.index().chainOf(region)) {
            if (mines.isMine(link.id())) {
                return link;
            }
        }
        return null;
    }

    /** The server's colour. Shared with the welcome banners and the sidebar title. */
    public static final TextColor EMBER = TextColor.color(0xE8382B);
    public static final TextColor EMBER_PALE = TextColor.color(0xFFD2B0);
}
