package com.thestill.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.thestill.descent.DescentService;
import com.thestill.economy.Money;
import com.thestill.mine.DepthPlan;
import com.thestill.economy.SellService;
import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.rank.Rank;
import com.thestill.rank.RankService;
import com.thestill.region.Region;
import com.thestill.ui.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/** Everything a player types. */
public final class PlayerCommands {

    private final Plugin plugin;
    private final Profiles profiles;
    private final SellService selling;
    private final RankService ranks;
    private final DescentService descent;

    private final com.thestill.ui.NametagService nametags;

    private com.thestill.role.RoleService roles;
    private com.thestill.config.Dial<String> storeUrl;

    private com.thestill.cosmetic.NickService nicks;
    private com.thestill.cosmetic.ThemeService themes;
    private com.thestill.cosmetic.NickPrompt prompt;

    /** Late-wired: the store needs the role table, which starts after commands are built. */
    public void withStore(com.thestill.role.RoleService roles,
                          com.thestill.config.Dial<String> storeUrl) {
        this.roles = roles;
        this.storeUrl = storeUrl;
    }

    /**
     * Late-wired for the same reason, and handed the <em>services</em> rather than a copy
     * of their logic.
     *
     * <p>That is not tidiness. Shadow Prison's {@code /nick <text>} carried its own perk
     * check and it was the wrong one — the menu gated renaming behind
     * {@code nick-custom-name} and the command checked only the colours entitlement, so
     * the gate could be walked round by typing instead of clicking. A command that holds
     * a service cannot hold a second opinion about who is allowed.
     */
    public void withCosmetics(com.thestill.cosmetic.NickService nicks,
                              com.thestill.cosmetic.ThemeService themes,
                              com.thestill.cosmetic.NickPrompt prompt) {
        this.nicks = nicks;
        this.themes = themes;
        this.prompt = prompt;
    }

    public PlayerCommands(Plugin plugin, Profiles profiles, SellService selling,
                          RankService ranks, DescentService descent,
                          com.thestill.ui.NametagService nametags) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.selling = selling;
        this.ranks = ranks;
        this.descent = descent;
        this.nametags = nametags;
    }

    public List<LiteralCommandNode<CommandSourceStack>> buildAll() {
        List<LiteralCommandNode<CommandSourceStack>> commands = new ArrayList<>();
        commands.add(sellCommand("sell"));
        commands.add(sellCommand("sellall"));
        commands.add(balanceCommand());
        commands.add(rankupCommand());
        commands.add(ranksCommand());
        commands.add(depthsCommand());
        commands.add(spawnCommand());
        commands.add(storeCommand());
        commands.add(nickCommand());
        // ONE spelling. It used to register both, on the theory that nobody should have
        // to guess which side of the Atlantic the server was written on — but two
        // commands in the tab list that do the same thing is a question, not a
        // convenience, and the owner asked it. Shadow Prison's is /chatcolor, this is a
        // copy of Shadow Prison's, so it is /chatcolor.
        commands.add(chatColourCommand("chatcolor"));
        return commands;
    }

    // ---- /nick, /chatcolor -----------------------------------------------

    /**
     * {@code /nick} — the menu, {@code clear}, or a name typed straight in.
     *
     * <p><b>All three go through {@link com.thestill.cosmetic.NickService}.</b> The typed
     * form is the one that matters: over on the source server it was a separate method
     * with a separate, weaker perk check, so the rank gate on renaming existed only in
     * the menu and could be stepped round by anybody who typed the command instead. There
     * is one funnel here and the command does not get an opinion.
     */
    private LiteralCommandNode<CommandSourceStack> nickCommand() {
        return Commands.literal("nick")
                .executes(context -> withPlayer(context, (player, profile) -> {
                    if (nicks == null) {
                        Msg.error(player, "Cosmetics are not loaded.");
                        return 0;
                    }
                    if (!nicks.canNick(profile)) {
                        Msg.error(player, "Recolouring your name is a rank perk. See /store.");
                        return 0;
                    }
                    new com.thestill.cosmetic.NickMenu(plugin, player, profiles, profile,
                            nicks, themes, prompt).open();
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("clear")
                        .executes(context -> withPlayer(context, (player, profile) -> {
                            if (nicks == null || !nicks.canNick(profile)) {
                                Msg.error(player, "You have no nickname to clear.");
                                return 0;
                            }
                            nicks.clear(player, profile);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.argument("name",
                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(context -> withPlayer(context, (player, profile) -> {
                            if (nicks == null) {
                                Msg.error(player, "Cosmetics are not loaded.");
                                return 0;
                            }
                            String raw = com.mojang.brigadier.arguments.StringArgumentType
                                    .getString(context, "name");
                            return nicks.apply(player, profile, raw)
                                    ? Command.SINGLE_SUCCESS : 0;
                        })))
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> chatColourCommand(String name) {
        return Commands.literal(name)
                .executes(context -> withPlayer(context, (player, profile) -> {
                    if (nicks == null) {
                        Msg.error(player, "Cosmetics are not loaded.");
                        return 0;
                    }
                    new com.thestill.cosmetic.ChatColourMenu(plugin, player, profiles, profile,
                            nicks, themes, prompt).open();
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("clear")
                        .executes(context -> withPlayer(context, (player, profile) -> {
                            // White, not blank — ChatDisplay.DEFAULT_CHAT_COLOR is
                            // "#FFFFFF" and the menu's own button says "Reset to White",
                            // so the command and the button have to land in one place.
                            profile.state().set(
                                    com.thestill.cosmetic.CosmeticKeys.CHAT_COLOUR, "#FFFFFF");
                            profile.state().set(
                                    com.thestill.cosmetic.CosmeticKeys.CHAT_THEME_ON, false);
                            profile.markDirty();
                            Msg.info(player, "Chat color reset to white.");
                            return Command.SINGLE_SUCCESS;
                        })))
                .build();
    }

    // ---- /sell, /sellall -------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> sellCommand(String name) {
        return Commands.literal(name)
                .executes(context -> withPlayer(context, (player, profile) -> {
                    if (selling.isBlockedByCreative(player)) {
                        Msg.error(player, "Selling is disabled in creative.");
                        return 0;
                    }
                    SellService.Result result = selling.sellAll(player, profile);
                    if (!result.soldAnything()) {
                        Msg.info(player, result.skipped() > 0
                                ? "Nothing sellable — " + result.skipped()
                                        + " stack(s) have no price."
                                : "You have nothing to sell.");
                        return 0;
                    }
                    announceSale(player, result);
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    /**
     * The sale, as an event rather than a chat line.
     *
     * <p>The build plan is specific about this: "an actual animated total with a
     * sound, not a grey chat line — the first thing that will make it feel like a
     * server rather than a plugin." The total counts up in the action bar over
     * half a second and lands on a chime.
     */
    private void announceSale(Player player, SellService.Result result) {
        final int frames = 8;
        for (int frame = 1; frame <= frames; frame++) {
            long shown = result.total() * frame / frames;
            boolean last = frame == frames;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.sendActionBar(Component.text("+ ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(Money.format(shown),
                                last ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                                TextDecoration.BOLD)));
                if (last) {
                    player.playSound(player.getLocation(),
                            Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.5f);
                    player.playSound(player.getLocation(),
                            Sound.ENTITY_PLAYER_LEVELUP, 0.35f, 1.8f);
                }
            }, frame);
        }
        player.sendMessage(Component.text("Sold ", NamedTextColor.DARK_GRAY)
                .append(Component.text(result.items(), NamedTextColor.WHITE))
                .append(Component.text(" item(s) for ", NamedTextColor.DARK_GRAY))
                .append(Component.text(Money.format(result.total()), NamedTextColor.GREEN))
                .append(result.skipped() == 0
                        ? Component.empty()
                        : Component.text("  (" + result.skipped() + " unpriced, kept)",
                        NamedTextColor.DARK_GRAY)));
    }

    // ---- /bal ------------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> balanceCommand() {
        return Commands.literal("bal")
                .executes(context -> withPlayer(context, (player, profile) -> {
                    Msg.ok(player, "Balance: " + Money.format(profile.balance()));
                    long pending = selling.appraise(player);
                    if (pending > 0) {
                        Msg.info(player, "Carrying " + Money.format(pending) + " unsold.");
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    // ---- /rankup ---------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> rankupCommand() {
        return Commands.literal("rankup")
                .executes(context -> withPlayer(context, (player, profile) -> {
                    RankService.Result result = ranks.rankup(profile);
                    switch (result.status()) {
                        case AT_MAX -> Msg.warn(player, "You are at the top of the ladder.");
                        case TOO_POOR -> {
                            Msg.error(player, "Rank " + result.to().display() + " costs "
                                    + Money.format(result.cost()) + ".");
                            Msg.info(player, "You need " + Money.format(result.shortBy())
                                    + " more.");
                        }
                        case PROMOTED -> celebrateRankup(player, result);
                    }
                    return result.promoted() ? Command.SINGLE_SUCCESS : 0;
                }))
                .build();
    }

    private void celebrateRankup(Player player, RankService.Result result) {
        Rank rank = result.to();
        player.showTitle(net.kyori.adventure.title.Title.title(
                Component.text(rank.display(), rank.colour(), TextDecoration.BOLD),
                Component.text("rank up", NamedTextColor.DARK_GRAY),
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(200),
                        java.time.Duration.ofMillis(1400),
                        java.time.Duration.ofMillis(400))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        // A rank up changes the team NAME, which is what tab sorts on, so the
        // nametag has to be rebuilt rather than just recoloured.
        nametags.refresh(player);

        Msg.ok(player, "You are now rank " + rank.display() + ".");
        Region next = descent.depths().stream()
                .filter(depth -> ranks.requiredRank(depth) == rank.index())
                .findFirst().orElse(null);
        if (next != null) {
            int number = next.state().get(RankService.DEPTH_NUMBER);
            player.sendMessage(Component.text("  D" + number + " is open to you now. ",
                            NamedTextColor.DARK_GRAY)
                    .append(Component.text("Find the way down.", NamedTextColor.AQUA)));
        }
    }

    // ---- /ranks ----------------------------------------------------------

    private LiteralCommandNode<CommandSourceStack> ranksCommand() {
        return Commands.literal("ranks")
                .executes(context -> withPlayer(context, (player, profile) -> {
                    Rank current = ranks.rankOf(profile);
                    Msg.ok(player, "The ladder — you are at " + current.display());
                    // Three rungs above and one below is enough context to see
                    // where you are without scrolling past twenty six lines.
                    int from = Math.max(0, current.index() - 1);
                    int to = Math.min(ranks.ladder().size() - 1, current.index() + 3);
                    for (int i = from; i <= to; i++) {
                        Rank rank = ranks.ladder().byIndex(i);
                        boolean isCurrent = i == current.index();
                        long cost = ranks.costOf(rank);
                        boolean affordable = profile.balance() >= cost;
                        player.sendMessage(Component.text(isCurrent ? "  > " : "    ",
                                        NamedTextColor.DARK_AQUA)
                                .append(Component.text(rank.display(), rank.colour()))
                                .append(Component.text("  " + Money.format(cost),
                                        i <= current.index() ? NamedTextColor.DARK_GRAY
                                                : affordable ? NamedTextColor.GREEN
                                                : NamedTextColor.GRAY)));
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    // ---- /depths, /spawn -------------------------------------------------

    /**
     * The route, as a record of how far you have got.
     *
     * <p>Deliberately not a menu you can travel from. It answers "how deep am I
     * allowed" and "how deep have I actually been", which are different numbers,
     * and the gap between them is the game.
     */
    private LiteralCommandNode<CommandSourceStack> depthsCommand() {
        return Commands.literal("depths")
                .executes(context -> withPlayer(context, (player, profile) -> {
                    Msg.ok(player, "The Depths");
                    for (Region depth : descent.depths()) {
                        boolean ranked = ranks.canAccess(profile, depth);
                        boolean reached = descent.hasReached(profile, depth);
                        int number = depth.state().get(RankService.DEPTH_NUMBER);
                        Rank needed = ranks.rankNeededFor(depth);
                        Component status;
                        if (!ranked) {
                            status = Component.text("needs rank " + needed.display(),
                                    NamedTextColor.DARK_GRAY);
                        } else if (!reached) {
                            status = Component.text("open — you have never been there",
                                    NamedTextColor.GOLD);
                        } else {
                            status = Component.text("reached", NamedTextColor.GREEN);
                        }
                        player.sendMessage(Component.text("  D" + number + "  ",
                                        ranked ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY)
                                .append(status));
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    /**
     * Works above the fog. Refused below it.
     *
     * <p><b>The fog line is the rule.</b> Above it you are on the rig, where getting
     * about is convenience and nobody is risking anything; a player who wandered to
     * the far side of a 193-wide deck should not have to walk back. The moment you
     * drop through the hole, that stops: you are carrying something, and the walk
     * home is the price of everything you picked up. The winch is the only way out
     * from there.
     *
     * <p>Admins are exempt, because someone who teleported into D26 to look at a
     * wall should not have to ride out.
     */
    /**
     * {@code /store} — the ranks you can buy.
     *
     * <p>Its own command rather than a page of something else, because a player who
     * wants to give you money should never have to go looking. Everything it does is
     * show a menu and hand out a URL; no money moves on this server.
     */
    private LiteralCommandNode<CommandSourceStack> storeCommand() {
        return Commands.literal("store")
                .executes(context -> {
                    if (!(context.getSource().getSender() instanceof org.bukkit.entity.Player player)) {
                        return 0;
                    }
                    var profile = profiles.get(player.getUniqueId());
                    if (profile == null || roles == null) {
                        com.thestill.ui.Msg.error(player, "The store is not open yet.");
                        return 0;
                    }
                    new com.thestill.role.StoreMenu(plugin, player, roles, profiles, profile, storeUrl,
                            com.thestill.role.StoreMenu.Page.HOME).open();
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> spawnCommand() {
        return Commands.literal("spawn")
                .executes(context -> withPlayer(context, (player, profile) -> {
                    boolean belowTheFog = player.getWorld().equals(
                            org.bukkit.Bukkit.getWorld(
                                    com.thestill.world.StillWorld.WORLD_KEY))
                            && player.getLocation().getBlockY() < DepthPlan.FOG_Y;
                    if (belowTheFog && !player.hasPermission("thestill.admin")) {
                        Msg.error(player, "Nothing carries you out of the Depths.");
                        Msg.info(player, "Walk to a station and haul on the winch. "
                                + "Every fifth depth has one.");
                        return 0;
                    }
                    var hub = descent.hub();
                    if (hub == null) {
                        Msg.error(player, "There is no hub yet.");
                        return 0;
                    }
                    player.teleport(hub);
                    Msg.ok(player, "Back to the rigs.");
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    // ---- plumbing --------------------------------------------------------

    @FunctionalInterface
    private interface PlayerAction {
        int run(Player player, Profile profile);
    }

    /** Resolves sender and profile once, so no command has to repeat the guard. */
    private int withPlayer(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                           PlayerAction action) {
        if (!(context.getSource().getSender() instanceof Player player)) {
            Msg.error(context.getSource().getSender(), "Players only.");
            return 0;
        }
        Profile profile = profiles.get(player.getUniqueId());
        if (profile == null) {
            Msg.error(player, "Your profile is still loading — try again in a moment.");
            return 0;
        }
        return action.run(player, profile);
    }
}
