package com.thestill.admin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.thestill.ui.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Date;
import java.util.Optional;

/**
 * {@code /adminset <player>} — opens the panel. {@code /adminset <player> log} prints
 * what has already been done to them.
 *
 * <p>The lookup is <b>async</b>, and that is the whole reason this class is not four
 * lines. Shadow Prison can resolve any player instantly because every profile that
 * ever joined is permanently in memory; here {@link com.thestill.player.Profiles}
 * caches online players only, so finding somebody offline is a database read — and
 * {@code Database.read} throws if it is called on the server thread, on purpose. So:
 * look up async, hop back to the main thread, then open. See {@link OfflineProfiles},
 * whose five rules this command is the front door to.
 */
public final class AdminSetCommand {

    /** Re-read at every click as well as here. A menu is an ability, not a picture. */
    public static final String PERMISSION = "thestill.admin";

    private final Plugin plugin;
    private final OfflineProfiles offline;
    private final AdminSetContext context;

    public AdminSetCommand(Plugin plugin, OfflineProfiles offline, AdminSetContext context) {
        this.plugin = plugin;
        this.offline = offline;
        this.context = context;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("adminset")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> usage(context.getSource().getSender()))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> offline.suggest(30).thenApply(names -> {
                            String prefix = builder.getRemaining().toLowerCase();
                            names.stream()
                                    .filter(name -> name.toLowerCase().startsWith(prefix))
                                    .forEach(builder::suggest);
                            return builder.build();
                        }))
                        .executes(ctx -> open(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "player")))
                        // A subcommand under the name, not beside it: `/adminset log`
                        // would otherwise be a player called "log", and Brigadier would
                        // pick whichever branch it saw first.
                        .then(Commands.literal("log")
                                .executes(ctx -> log(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player")))))
                .build();
    }

    private int usage(CommandSender sender) {
        Msg.info(sender, "/adminset <player> — the panel. /adminset <player> log — what was done to them.");
        return Command.SINGLE_SUCCESS;
    }

    private int open(CommandSender sender, String name) {
        if (!(sender instanceof Player admin)) {
            Msg.error(sender, "Players only — this is a menu. The console has /still.");
            return 0;
        }
        find(sender, name, target -> {
            if (!admin.isOnline()) {
                return;
            }
            if (!target.live()) {
                Msg.info(admin, target.name() + " is offline — you are editing their "
                        + "stored record. It will refuse to save if they log in.");
            }
            AdminSetMenu.home(plugin, admin, context, target).open();
        });
        return Command.SINGLE_SUCCESS;
    }

    private int log(CommandSender sender, String name) {
        find(sender, name, target -> context.audit().recent(target.uuid(), 30)
                .thenAccept(entries -> offline.onMain(() -> {
                    Msg.ok(sender, "Admin changes to " + target.name()
                            + " — " + entries.size() + " on record");
                    if (entries.isEmpty()) {
                        Msg.info(sender, "Nothing has been done to them.");
                        return;
                    }
                    for (AdminAudit.Entry entry : entries) {
                        Msg.raw(sender, Component.text("  " + new Date(entry.at()) + "  ",
                                        NamedTextColor.DARK_GRAY)
                                .append(Component.text(entry.adminName(), NamedTextColor.WHITE,
                                        TextDecoration.BOLD))
                                .append(Component.text("  " + entry.action() + "  ",
                                        NamedTextColor.YELLOW))
                                .append(Component.text((entry.before() == null ? "-" : entry.before())
                                                + " → "
                                                + (entry.after() == null ? "-" : entry.after()),
                                        NamedTextColor.GRAY)));
                    }
                })));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Resolves a name, then runs the work on the main thread.
     *
     * <p><b>No row means no target</b> — {@link OfflineProfiles} rule 4. Minting a
     * blank profile for a misspelled name is how an admin spends ten minutes editing
     * somebody who does not exist and then files a bug about it not saving.
     */
    private void find(CommandSender sender, String name,
                      java.util.function.Consumer<AdminTarget> work) {
        offline.find(name).whenComplete((found, error) -> offline.onMain(() -> {
            if (error != null) {
                Msg.error(sender, "Could not look that name up — see the console.");
                return;
            }
            Optional<AdminTarget> target = found;
            if (target.isEmpty()) {
                Msg.error(sender, "Nobody called '" + name + "' has ever joined.");
                return;
            }
            work.accept(target.get());
        }));
    }
}
