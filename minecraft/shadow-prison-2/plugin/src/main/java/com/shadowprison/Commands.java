package com.shadowprison;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.shadowprison.dial.ConfigMenu;
import com.shadowprison.grant.ClaimMenu;
import com.shadowprison.item.ItemDef;
import com.shadowprison.menu.Ui;
import com.shadowprison.money.Currency;
import com.shadowprison.money.Money;
import com.shadowprison.profile.Profile;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Every command, registered through Brigadier (no plugin.yml entries to drift). Admin nodes are
 * hidden from players' tab-complete by {@code requires}, and every admin ACTION re-checks too.
 */
final class Commands {

    private final ShadowPrison plugin;

    Commands(ShadowPrison plugin) {
        this.plugin = plugin;
    }

    void register(io.papermc.paper.command.brigadier.Commands registrar) {
        registrar.register(menu(), "Open the Shadow Menu", List.of("m"));
        registrar.register(claim(), "Rewards waiting for you", List.of());
        registrar.register(balance(), "Your coins", List.of("bal", "money"));
        registrar.register(config(), "Server configuration", List.of());
        registrar.register(admin(), "Shadow Prison administration", List.of());
    }

    private static Player player(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender() instanceof Player p ? p : null;
    }

    private LiteralCommandNode<CommandSourceStack> menu() {
        return io.papermc.paper.command.brigadier.Commands.literal("menu")
                .executes(ctx -> {
                    Player p = player(ctx);
                    if (p != null) {
                        new MainMenu(plugin).open(p);
                    }
                    return 1;
                }).build();
    }

    private LiteralCommandNode<CommandSourceStack> claim() {
        return io.papermc.paper.command.brigadier.Commands.literal("claim")
                .executes(ctx -> {
                    Player p = player(ctx);
                    if (p != null) {
                        ClaimMenu.open(plugin, plugin.grants(), p);
                    }
                    return 1;
                }).build();
    }

    private LiteralCommandNode<CommandSourceStack> balance() {
        return io.papermc.paper.command.brigadier.Commands.literal("balance")
                .executes(ctx -> {
                    Player p = player(ctx);
                    Profile profile = p == null ? null : plugin.profiles().get(p);
                    if (profile != null) {
                        p.sendMessage(Ui.text("Purse: ", Ui.MUTED)
                                .append(Ui.text(Money.format(profile.balance(Currency.COINS)), Ui.ACCENT)));
                    }
                    return 1;
                }).build();
    }

    private LiteralCommandNode<CommandSourceStack> config() {
        return io.papermc.paper.command.brigadier.Commands.literal("config")
                .requires(src -> src.getSender().hasPermission(ConfigMenu.PERMISSION))
                .executes(ctx -> {
                    Player p = player(ctx);
                    if (p != null && p.hasPermission(ConfigMenu.PERMISSION)) {
                        new ConfigMenu(plugin.dials(), plugin.prompts()).open(p);
                    }
                    return 1;
                }).build();
    }

    /** /sp — the admin instrument: health, give, money. */
    private LiteralCommandNode<CommandSourceStack> admin() {
        return io.papermc.paper.command.brigadier.Commands.literal("sp")
                .requires(src -> src.getSender().hasPermission("shadow.admin"))
                .then(io.papermc.paper.command.brigadier.Commands.literal("health").executes(ctx -> {
                    var s = ctx.getSource().getSender();
                    s.sendMessage(Ui.text("store: ", Ui.MUTED).append(Ui.text(
                            plugin.db().committedTasks() + " writes committed, queue " + plugin.db().queueDepth(), Ui.ACCENT)));
                    s.sendMessage(Ui.text("dials: " + plugin.dials().size() + " · items: " + plugin.items().all().size()
                            + " · online profiles: " + plugin.profiles().online().size(), Ui.MUTED));
                    return 1;
                }))
                .then(io.papermc.paper.command.brigadier.Commands.literal("give")
                        .then(io.papermc.paper.command.brigadier.Commands.argument("item", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    plugin.items().all().forEach(d -> b.suggest(d.id()));
                                    return b.buildFuture();
                                })
                                .executes(ctx -> give(ctx, 1))
                                .then(io.papermc.paper.command.brigadier.Commands.argument("amount", IntegerArgumentType.integer(1, 2304))
                                        .executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))
                .then(io.papermc.paper.command.brigadier.Commands.literal("money")
                        .then(io.papermc.paper.command.brigadier.Commands.argument("player", StringArgumentType.word())
                                .then(io.papermc.paper.command.brigadier.Commands.argument("amount", StringArgumentType.word())
                                        .executes(this::money))))
                .build();
    }

    /** Gives N of an item through Grants — overflow lands in /claim, which is the Phase 0 demo. */
    private int give(CommandContext<CommandSourceStack> ctx, int amount) {
        Player p = player(ctx);
        if (p == null || !p.hasPermission("shadow.admin")) {
            return 0;
        }
        ItemDef def = plugin.items().def(StringArgumentType.getString(ctx, "item"));
        if (def == null) {
            p.sendMessage(Ui.text("Unknown item.", Ui.BAD));
            return 0;
        }
        String source = "admin:" + p.getName();
        int left = def.unique() ? Math.min(amount, 64) : amount;
        int per = def.unique() ? 1 : def.material().getMaxStackSize();
        while (left > 0) {
            int n = Math.min(per, left);
            plugin.grants().give(p, plugin.items().create(def.id(), n, source), source);
            left -= n;
        }
        p.sendMessage(Ui.text("Gave " + amount + "x ", Ui.GOOD).append(Ui.text(def.name(), def.rarity().color())));
        return 1;
    }

    private int money(CommandContext<CommandSourceStack> ctx) {
        var sender = ctx.getSource().getSender();
        if (!sender.hasPermission("shadow.admin")) {
            return 0;
        }
        Player target = Bukkit.getPlayerExact(StringArgumentType.getString(ctx, "player"));
        Profile profile = target == null ? null : plugin.profiles().get(target);
        if (profile == null) {
            sender.sendMessage(Ui.text("That player is not online.", Ui.BAD));
            return 0;
        }
        var amount = Money.parse(StringArgumentType.getString(ctx, "amount"));
        if (amount.isEmpty() || amount.get() == 0) {
            sender.sendMessage(Ui.text("Amount must be a positive number like 1500, 2.5k or 3m.", Ui.BAD));
            return 0;
        }
        if (!plugin.wallets().credit(profile, Currency.COINS, amount.get(), "admin:" + sender.getName())) {
            sender.sendMessage(Ui.text("That would exceed the maximum balance.", Ui.BAD));
            return 0;
        }
        sender.sendMessage(Ui.text("Gave " + Money.format(amount.get()) + " to " + target.getName() + ".", Ui.GOOD));
        return 1;
    }
}
