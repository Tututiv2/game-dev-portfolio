package com.skyclone.api.command;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * A command, as plain strings — no Brigadier trees to get wrong. Core registers it, routes
 * {@code /name arg1 arg2...} to {@link #executor}, and lists it in {@code /commands}.
 *
 * <pre>
 *   Sky.commands().register(SkyCommand.of("bz", "Open the Bazaar")
 *       .alias("bazaar").category("Economy").playerOnly()
 *       .run((s, a) -> BazaarMenu.open((Player) s)));
 * </pre>
 *
 * Admin tools go under {@code /sb}: {@code Sky.commands().admin("slayer", ...)} makes
 * {@code /sb slayer ...} (permission skyclone.admin).
 */
public final class SkyCommand {

    @FunctionalInterface
    public interface Executor { void run(CommandSender sender, String[] args); }

    @FunctionalInterface
    public interface Completer { List<String> complete(CommandSender sender, String[] args); }

    public final String name;
    public final String description;
    public final List<String> aliases = new ArrayList<>();
    public String usage = "";
    public String permission;          // null = everyone
    public boolean playerOnly;
    public String category = "General";
    public Executor executor = (s, a) -> {};
    public Completer completer = (s, a) -> List.of();

    private SkyCommand(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public static SkyCommand of(String name, String description) { return new SkyCommand(name, description); }

    public SkyCommand alias(String... a) { aliases.addAll(List.of(a)); return this; }
    public SkyCommand usage(String u) { usage = u; return this; }
    public SkyCommand permission(String p) { permission = p; return this; }
    public SkyCommand admin() { permission = "skyclone.admin"; category = "Admin"; return this; }
    public SkyCommand playerOnly() { playerOnly = true; return this; }
    public SkyCommand category(String c) { category = c; return this; }
    public SkyCommand run(Executor e) { executor = e; return this; }
    public SkyCommand tab(Completer c) { completer = c; return this; }
}
