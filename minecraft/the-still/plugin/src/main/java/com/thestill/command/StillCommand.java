package com.thestill.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.thestill.config.Dial;
import com.thestill.config.DialRegistry;
import com.thestill.economy.Money;
import com.thestill.economy.PriceTable;
import com.thestill.mine.DepthPlan;
import com.thestill.mine.DepthSetup;
import com.thestill.mine.MineService;
import com.thestill.mine.OreTable;
import com.thestill.player.Onboarding;
import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.probe.CrashProbe;
import com.thestill.rank.RankService;
import com.thestill.region.Cuboid;
import com.thestill.region.Region;
import com.thestill.region.RegionService;
import com.thestill.region.RegionType;
import com.thestill.state.StateKey;
import com.thestill.store.Database;
import com.thestill.ui.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /still} — everything Phase 0 exposes.
 *
 * <p>Registered through Brigadier at runtime, so there is no {@code commands:}
 * block to maintain. Shadow Prison's {@code plugin.yml} grew to a hand-written
 * 700 lines and adding a command was a five-step checklist where missing any
 * step failed silently.
 */
public final class StillCommand {

    /**
     * Public because a menu this command opens has to re-check it at click time.
     *
     * <p>An open inventory is a snapshot — a page drawn for an admin keeps working
     * after they stop being one — so {@code ItemVaultMenu} asks again on every
     * click. It must ask about the <em>same</em> node this command gates on, and two
     * copies of a permission string is a disagreement waiting to happen.
     */
    public static final String PERMISSION = "thestill.admin";

    private final Plugin plugin;
    private final DialRegistry dials;
    private final RegionService regions;
    private final Profiles profiles;
    private final Database database;
    private final CrashProbe probe;
    private final DepthSetup depths;
    private final MineService mines;
    private final PriceTable prices;
    private final RankService ranks;
    private final com.thestill.world.StillWorld stillWorld;
    private final Onboarding onboarding;
    private final com.thestill.role.RoleService roles;
    private final com.thestill.ui.NametagService nametags;

    /** Two-corner selection per player, for defining regions. */
    private final Map<UUID, Location[]> selections = new HashMap<>();

    public StillCommand(Plugin plugin, DialRegistry dials, RegionService regions,
                        Profiles profiles, Database database, CrashProbe probe,
                        DepthSetup depths, MineService mines, PriceTable prices,
                        RankService ranks, com.thestill.world.StillWorld stillWorld,
                        Onboarding onboarding, com.thestill.role.RoleService roles,
                        com.thestill.ui.NametagService nametags) {
        this.roles = roles;
        this.nametags = nametags;
        this.stillWorld = stillWorld;
        this.onboarding = onboarding;
        this.plugin = plugin;
        this.dials = dials;
        this.regions = regions;
        this.profiles = profiles;
        this.database = database;
        this.probe = probe;
        this.depths = depths;
        this.mines = mines;
        this.prices = prices;
        this.ranks = ranks;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("still")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> info(context.getSource().getSender()))
                .then(Commands.literal("info")
                        .executes(context -> info(context.getSource().getSender())))
                .then(Commands.literal("reload")
                        .executes(context -> reload(context.getSource().getSender())))
                .then(dialBranch())
                .then(selectionBranch(1))
                .then(selectionBranch(2))
                .then(regionBranch())
                .then(probeBranch())
                .then(databaseBranch())
                .then(setupBranch())
                .then(verifyBranch())
                .then(newPlayerBranch())
                .then(lampBranch())
                .then(roleBranch())
                .then(cosmeticBranch())
                .then(mineBranch())
                .then(economyBranch())
                .build();
    }

    // ---- /still info | reload -------------------------------------------

    private int info(CommandSender sender) {
        Msg.ok(sender, "The Still — Phase 0 skeleton");
        Msg.raw(sender, Msg.field("dials", dials.size() + " registered"));
        Msg.raw(sender, Msg.field("regions", regions.index().size() + " defined"));
        Msg.raw(sender, Msg.field("profiles cached", String.valueOf(profiles.cachedCount())));
        Msg.raw(sender, Msg.field("write queue", database.queueDepth() + " pending, "
                + database.committedTasks() + " committed"));
        Msg.raw(sender, Msg.field("crash probe",
                probe.isWriting() ? "writing, seq " + probe.sequence() : "idle"));
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandSender sender) {
        int changed = dials.reload();
        Msg.ok(sender, changed == 0
                ? "Reloaded dials.yml — nothing changed."
                : "Reloaded dials.yml — " + changed + " dial(s) changed.");
        return Command.SINGLE_SUCCESS;
    }

    // ---- /still dial ----------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> dialBranch() {
        return Commands.literal("dial")
                .then(Commands.literal("list")
                        .executes(context -> listDials(context.getSource().getSender(), ""))
                        .then(Commands.argument("filter", StringArgumentType.word())
                                .executes(context -> listDials(context.getSource().getSender(),
                                        StringArgumentType.getString(context, "filter")))))
                .then(Commands.literal("set")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    dials.all().forEach(dial -> builder.suggest(dial.key()));
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(context -> setDial(
                                                context.getSource().getSender(),
                                                StringArgumentType.getString(context, "key"),
                                                StringArgumentType.getString(context, "value"))))));
    }

    private int listDials(CommandSender sender, String filter) {
        Msg.ok(sender, "Dials" + (filter.isEmpty() ? "" : " matching '" + filter + "'"));
        int shown = 0;
        for (Dial<?> dial : dials.all()) {
            if (!filter.isEmpty() && !dial.key().contains(filter)) {
                continue;
            }
            shown++;
            Msg.raw(sender, Component.text("  " + dial.key() + " = ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(String.valueOf(dial.get()),
                            dial.isDefault() ? NamedTextColor.WHITE : NamedTextColor.GOLD))
                    .append(dial.isDefault()
                            ? Component.empty()
                            : Component.text("  (default " + dial.defaultValue() + ")",
                            NamedTextColor.DARK_GRAY)));
        }
        Msg.info(sender, shown + " of " + dials.size() + " shown.");
        return Command.SINGLE_SUCCESS;
    }

    private int setDial(CommandSender sender, String key, String value) {
        Dial<?> dial = dials.byKey(key);
        if (dial == null) {
            Msg.error(sender, "No dial named '" + key + "'.");
            return 0;
        }
        String problem = dial.applyFromString(value);
        if (problem != null) {
            Msg.error(sender, "Rejected: " + problem);
            return 0;
        }
        dials.save();
        Msg.ok(sender, key + " = " + dial.get()
                + (dial.isDefault() ? " (back to default)" : ""));
        return Command.SINGLE_SUCCESS;
    }

    // ---- /still pos1 | pos2 ---------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> selectionBranch(int corner) {
        return Commands.literal("pos" + corner)
                .executes(context -> {
                    if (!(context.getSource().getSender() instanceof Player player)) {
                        Msg.error(context.getSource().getSender(), "Players only.");
                        return 0;
                    }
                    Location location = player.getLocation();
                    Location[] selection = selections.computeIfAbsent(
                            player.getUniqueId(), id -> new Location[2]);
                    selection[corner - 1] = location;
                    Msg.ok(player, "Corner " + corner + " set to "
                            + location.getBlockX() + ", " + location.getBlockY()
                            + ", " + location.getBlockZ());
                    return Command.SINGLE_SUCCESS;
                });
    }

    // ---- /still region --------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> regionBranch() {
        return Commands.literal("region")
                .then(Commands.literal("here")
                        .executes(context -> regionHere(context.getSource().getSender())))
                .then(Commands.literal("list")
                        .executes(context -> listRegions(context.getSource().getSender())))
                .then(Commands.literal("stats")
                        .executes(context -> regionStats(context.getSource().getSender())))
                .then(Commands.literal("at")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    plugin.getServer().getWorlds()
                                            .forEach(loaded -> builder.suggest(loaded.getName()));
                                    return builder.buildFuture();
                                })
                                .then(intArg("x").then(intArg("y").then(intArg("z")
                                        .executes(this::regionAt))))))
                .then(Commands.literal("info")
                        .then(regionIdArgument()
                                .executes(context -> regionInfo(context.getSource().getSender(),
                                        StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("define")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            for (RegionType type : RegionType.values()) {
                                                builder.suggest(type.id());
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> defineRegion(context.getSource().getSender(),
                                                StringArgumentType.getString(context, "id"),
                                                StringArgumentType.getString(context, "type"),
                                                null))
                                        .then(regionIdArgument("parent")
                                                .executes(context -> defineRegion(
                                                        context.getSource().getSender(),
                                                        StringArgumentType.getString(context, "id"),
                                                        StringArgumentType.getString(context, "type"),
                                                        StringArgumentType.getString(context, "parent")))))))
                .then(defineAtBranch())
                .then(Commands.literal("delete")
                        .then(regionIdArgument()
                                .executes(context -> deleteRegion(context.getSource().getSender(),
                                        StringArgumentType.getString(context, "id"), false))
                                .then(Commands.literal("cascade")
                                        .executes(context -> deleteRegion(
                                                context.getSource().getSender(),
                                                StringArgumentType.getString(context, "id"), true)))))
                .then(Commands.literal("state")
                        .then(regionIdArgument()
                                .executes(context -> showState(context.getSource().getSender(),
                                        StringArgumentType.getString(context, "id")))
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(context -> setState(
                                                        context.getSource().getSender(),
                                                        StringArgumentType.getString(context, "id"),
                                                        StringArgumentType.getString(context, "key"),
                                                        StringArgumentType.getString(context, "value")))))));
    }

    /**
     * {@code /still region defineat <id> <type> <world> <x1 y1 z1> <x2 y2 z2> [parent]}
     *
     * <p>Explicit coordinates rather than a selection, so regions can be defined
     * from the console and from scripts. This is the path Phase 1 will seed
     * D1–D26 through, and the only way to define a region without a player
     * standing in the world.
     */
    private LiteralArgumentBuilder<CommandSourceStack> defineAtBranch() {
        // Built inside-out with locals rather than as one nested expression.
        // Nine levels of Brigadier nesting written as a paren pyramid is a
        // guaranteed miscount, and the compiler error points at the last line
        // rather than the one that is actually wrong.
        var z2 = intArg("z2")
                .executes(context -> defineAt(context, null))
                .then(regionIdArgument("parent")
                        .executes(context -> defineAt(context,
                                StringArgumentType.getString(context, "parent"))));
        var y2 = intArg("y2").then(z2);
        var x2 = intArg("x2").then(y2);
        var z1 = intArg("z1").then(x2);
        var y1 = intArg("y1").then(z1);
        var x1 = intArg("x1").then(y1);

        var world = Commands.argument("world", StringArgumentType.word())
                .suggests((context, builder) -> {
                    plugin.getServer().getWorlds()
                            .forEach(loaded -> builder.suggest(loaded.getName()));
                    return builder.buildFuture();
                })
                .then(x1);

        var type = Commands.argument("type", StringArgumentType.word())
                .suggests((context, builder) -> {
                    for (RegionType value : RegionType.values()) {
                        builder.suggest(value.id());
                    }
                    return builder.buildFuture();
                })
                .then(world);

        return Commands.literal("defineat")
                .then(Commands.argument("id", StringArgumentType.word()).then(type));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer>
    intArg(String name) {
        return Commands.argument(name, IntegerArgumentType.integer());
    }

    private int defineAt(CommandContext<CommandSourceStack> context, String parentId) {
        CommandSender sender = context.getSource().getSender();
        String id = StringArgumentType.getString(context, "id");
        String typeName = StringArgumentType.getString(context, "type");
        String worldName = StringArgumentType.getString(context, "world");

        RegionType type = RegionType.parse(typeName);
        if (type == null) {
            Msg.error(sender, "Unknown type '" + typeName + "'. One of: depth, shaft, rig, hub");
            return 0;
        }
        if (!Region.isValidId(id)) {
            Msg.error(sender, "Bad id '" + id
                    + "'. Lowercase letters, digits and _ - . + only, max 64 characters. "
                    + "Use a dot for namespacing: depth.d7, shaft.d7-north");
            return 0;
        }
        if (plugin.getServer().getWorld(worldName) == null) {
            Msg.error(sender, "No loaded world named '" + worldName + "'.");
            return 0;
        }
        Cuboid bounds = new Cuboid(worldName,
                IntegerArgumentType.getInteger(context, "x1"),
                IntegerArgumentType.getInteger(context, "y1"),
                IntegerArgumentType.getInteger(context, "z1"),
                IntegerArgumentType.getInteger(context, "x2"),
                IntegerArgumentType.getInteger(context, "y2"),
                IntegerArgumentType.getInteger(context, "z2"));
        try {
            Region region = regions.define(id, type, parentId, bounds);
            Msg.ok(sender, "Defined " + region.id() + " (" + type.id() + ", "
                    + bounds.volume() + " blocks, " + bounds.chunkCount() + " chunks"
                    + (parentId == null ? "" : ", inside " + parentId) + ")");
        } catch (IllegalArgumentException e) {
            // Validation happens in the index before anything is persisted, so a
            // rejection here means nothing anywhere was modified.
            Msg.error(sender, "Rejected: " + e.getMessage());
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
    regionIdArgument() {
        return regionIdArgument("id");
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
    regionIdArgument(String name) {
        return Commands.argument(name, StringArgumentType.word())
                .suggests((context, builder) -> {
                    regions.index().all().forEach(region -> builder.suggest(region.id()));
                    return builder.buildFuture();
                });
    }

    private int regionHere(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.error(sender, "Players only.");
            return 0;
        }
        Region region = regions.regionFor(player);
        if (region == null) {
            Msg.info(player, "You are not in any defined region.");
            return Command.SINGLE_SUCCESS;
        }
        Msg.ok(player, "You are in " + region.id());
        for (Region ancestor : regions.index().chainOf(region)) {
            Msg.raw(player, Msg.field(ancestor.type().id(), ancestor.id()));
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /still region at <world> <x> <y> <z>} — what the index actually
     * answers for one block, and the full nesting chain above it.
     *
     * <p>Console-callable, so the lookup that every later system depends on can
     * be checked without standing in the world.
     */
    private int regionAt(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String worldName = StringArgumentType.getString(context, "world");
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");

        Region deepest = regions.index().deepestAt(worldName, x, y, z);
        if (deepest == null) {
            Msg.info(sender, "No region at " + worldName + " " + x + " " + y + " " + z);
            return Command.SINGLE_SUCCESS;
        }
        Msg.ok(sender, "deepest: " + deepest.id() + " (" + deepest.type().id() + ")");
        for (Region ancestor : regions.index().chainOf(deepest)) {
            Msg.raw(sender, Msg.field("nesting " + ancestor.nesting(),
                    ancestor.id() + "  " + ancestor.type().id()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int listRegions(CommandSender sender) {
        var all = regions.index().all();
        Msg.ok(sender, all.size() + " region(s)");
        all.forEach(region -> Msg.raw(sender, Component.text(
                "  " + "  ".repeat(region.nesting()) + region.id(), NamedTextColor.WHITE)
                .append(Component.text("  " + region.type().id()
                        + "  " + region.bounds().volume() + " blocks", NamedTextColor.DARK_GRAY))));
        return Command.SINGLE_SUCCESS;
    }

    private int regionStats(CommandSender sender) {
        Msg.ok(sender, "Region index");
        Msg.raw(sender, Msg.field("regions", String.valueOf(regions.index().size())));
        regions.index().chunkEntryCounts().forEach((world, count) ->
                Msg.raw(sender, Msg.field(world, count + " indexed chunk(s)")));
        return Command.SINGLE_SUCCESS;
    }

    private int regionInfo(CommandSender sender, String id) {
        Region region = regions.index().byId(id);
        if (region == null) {
            Msg.error(sender, "No region '" + id + "'.");
            return 0;
        }
        Msg.ok(sender, region.id());
        Msg.raw(sender, Msg.field("type", region.type().id()));
        Msg.raw(sender, Msg.field("parent", region.parentId() == null ? "-" : region.parentId()));
        Msg.raw(sender, Msg.field("nesting", String.valueOf(region.nesting())));
        Msg.raw(sender, Msg.field("bounds", region.bounds().toString()));
        Msg.raw(sender, Msg.field("volume", region.bounds().volume() + " blocks"));
        Msg.raw(sender, Msg.field("chunks", String.valueOf(region.bounds().chunkCount())));
        Msg.raw(sender, Msg.field("state", region.state().size() + " key(s)"));
        List<Region> children = regions.index().childrenOf(id);
        if (!children.isEmpty()) {
            Msg.raw(sender, Msg.field("children",
                    children.stream().map(Region::id).reduce((a, b) -> a + ", " + b).orElse("")));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int defineRegion(CommandSender sender, String id, String typeName, String parentId) {
        if (!(sender instanceof Player player)) {
            Msg.error(sender, "Players only — region definition uses your selection.");
            return 0;
        }
        Location[] selection = selections.get(player.getUniqueId());
        if (selection == null || selection[0] == null || selection[1] == null) {
            Msg.error(player, "Set both corners first: /still pos1 and /still pos2");
            return 0;
        }
        RegionType type = RegionType.parse(typeName);
        if (type == null) {
            Msg.error(player, "Unknown type '" + typeName + "'. One of: depth, shaft, rig, hub");
            return 0;
        }
        if (!Region.isValidId(id)) {
            Msg.error(player, "Bad id '" + id
                    + "'. Lowercase letters, digits and _ - . + only, max 64 characters. "
                    + "Use a dot for namespacing: depth.d7, shaft.d7-north");
            return 0;
        }
        try {
            Cuboid bounds = Cuboid.between(selection[0], selection[1]);
            Region region = regions.define(id, type, parentId, bounds);
            Msg.ok(player, "Defined " + region.id() + " (" + type.id() + ", "
                    + bounds.volume() + " blocks, " + bounds.chunkCount() + " chunks)");
        } catch (IllegalArgumentException e) {
            // The index validated before anything was written, so a rejection
            // here means nothing changed anywhere.
            Msg.error(player, "Rejected: " + e.getMessage());
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private int deleteRegion(CommandSender sender, String id, boolean cascade) {
        try {
            int removed = regions.delete(id, cascade);
            if (removed == 0) {
                Msg.error(sender, "No region '" + id + "'.");
                return 0;
            }
            Msg.ok(sender, "Deleted " + removed + " region(s).");
        } catch (IllegalStateException e) {
            Msg.error(sender, e.getMessage());
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private int showState(CommandSender sender, String id) {
        Region region = regions.index().byId(id);
        if (region == null) {
            Msg.error(sender, "No region '" + id + "'.");
            return 0;
        }
        var serialised = region.state().serialise();
        Msg.ok(sender, id + " state — " + serialised.size() + " key(s)");
        serialised.forEach((key, value) -> Msg.raw(sender, Msg.field(key, value)));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Sets a region state value.
     *
     * <p>Exists in Phase 0 to prove the extension point end to end: set a key,
     * restart the server, read it back. Everything Phase 2 and Phase 4 record
     * about a shaft goes through this same path.
     */
    private int setState(CommandSender sender, String id, String key, String rawValue) {
        Region region = regions.index().byId(id);
        if (region == null) {
            Msg.error(sender, "No region '" + id + "'.");
            return 0;
        }
        try {
            double asNumber = Double.parseDouble(rawValue);
            if (!Double.isFinite(asNumber)) {
                Msg.error(sender, "Refusing a non-finite number.");
                return 0;
            }
            region.state().set(StateKey.ofDouble(key, 0.0), asNumber);
        } catch (NumberFormatException notANumber) {
            switch (rawValue.toLowerCase()) {
                case "true" -> region.state().set(StateKey.ofBoolean(key, false), true);
                case "false" -> region.state().set(StateKey.ofBoolean(key, false), false);
                default -> region.state().set(StateKey.ofString(key, ""), rawValue);
            }
        }
        regions.flushDirty();
        Msg.ok(sender, id + " state " + key + " = " + rawValue + " (queued for write)");
        return Command.SINGLE_SUCCESS;
    }

    // ---- /still probe ---------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> probeBranch() {
        return Commands.literal("probe")
                .then(Commands.literal("start").executes(context -> {
                    probe.startWriting();
                    Msg.ok(context.getSource().getSender(), "Probe writing from seq "
                            + probe.sequence() + ".");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("stop").executes(context -> {
                    probe.stopWriting();
                    Msg.ok(context.getSource().getSender(), "Probe stopped at seq "
                            + probe.sequence() + ".");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("reset").executes(context -> {
                    probe.reset();
                    Msg.ok(context.getSource().getSender(), "Probe reset to zero.");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("status").executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    Msg.ok(sender, probe.isWriting() ? "Probe is writing." : "Probe is idle.");
                    Msg.raw(sender, Msg.field("seq", String.valueOf(probe.sequence())));
                    return Command.SINGLE_SUCCESS;
                }));
    }

    // ---- /still db ------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> databaseBranch() {
        return Commands.literal("db")
                .then(Commands.literal("status").executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    Msg.ok(sender, "Store");
                    Msg.raw(sender, Msg.field("queue depth", String.valueOf(database.queueDepth())));
                    Msg.raw(sender, Msg.field("committed", String.valueOf(database.committedTasks())));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("flush").executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    int players = profiles.flushDirty();
                    int regionCount = regions.flushDirty();
                    Msg.ok(sender, "Queued " + players + " profile(s) and "
                            + regionCount + " region(s).");
                    return Command.SINGLE_SUCCESS;
                }));
    }

    // ---- /still newplayer -----------------------------------------------

    /**
     * {@code /still newplayer [who]} — become a brand-new player again.
     *
     * <p><b>This exists so the Phase 1 gate can be run more than once.</b> The gate is
     * "a brand-new player reaches D5 without asking a single question — watch someone
     * do it and don't help", and until now the only way to be a brand-new player was
     * to delete the database. That made the one test the whole phase is judged on cost
     * a server wipe, so it kept not being run.
     *
     * <p>Resets exactly what a first join sets: the onboarding flag, the balance, the
     * rank, and the inventory. It does <em>not</em> touch the world, so the mines stay
     * where they were and the run is repeatable within a session.
     */
    private LiteralArgumentBuilder<CommandSourceStack> newPlayerBranch() {
        return Commands.literal("newplayer")
                .executes(context -> newPlayer(context, null))
                .then(Commands.argument("who", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            plugin.getServer().getOnlinePlayers()
                                    .forEach(p -> builder.suggest(p.getName()));
                            return builder.buildFuture();
                        })
                        .executes(context -> newPlayer(context,
                                StringArgumentType.getString(context, "who"))));
    }

    private int newPlayer(CommandContext<CommandSourceStack> context, String who) {
        CommandSender sender = context.getSource().getSender();
        Player target = who == null
                ? (sender instanceof Player self ? self : null)
                : plugin.getServer().getPlayerExact(who);
        if (target == null) {
            Msg.error(sender, who == null
                    ? "Name a player — the console is not one."
                    : "No player online called '" + who + "'.");
            return 0;
        }
        Profile profile = profiles.get(target.getUniqueId());
        if (profile == null) {
            Msg.error(sender, "No profile loaded for " + target.getName() + ".");
            return 0;
        }

        profile.setBalance(0);
        profile.state().set(RankService.RANK_INDEX, 0);
        profile.state().set(Onboarding.KIT_GIVEN, false);
        profiles.saveDurable(profile);

        target.getInventory().clear();
        target.setLevel(0);
        target.setExp(0f);
        target.setHealth(Math.min(20.0, target.getMaxHealth()));
        target.setFoodLevel(20);

        // And then the actual first sixty seconds, exactly as a real join runs them:
        // kit, teleport to the Landing, three lines.
        onboarding.welcome(target, profile);

        Msg.ok(sender, target.getName() + " is a brand-new player again — "
                + "balance 0, rank 0, kit re-issued, on the Landing.");
        if (!target.equals(sender)) {
            Msg.info(target, "An admin has reset you to a new player.");
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---- /still lamp ----------------------------------------------------

    private com.thestill.fog.FogService fog;

    /**
     * Hands the command the lamp service rather than a second copy of what a lamp is.
     *
     * <p>Late-wired because the fog service starts after commands are constructed, and
     * because the item has to come from the service that also decides when it lights —
     * a command that built its own {@code ItemStack} would be a second definition of the
     * lamp, and one of the two would eventually stop matching.
     */
    public void withFog(com.thestill.fog.FogService fog) {
        this.fog = fog;
    }

    /** Late-wired for the same reason as {@link #withFog}: cosmetics start after commands. */
    public void withCosmetics(com.thestill.cosmetic.ThemeService themeService) {
        this.themeService = themeService;
    }

    private com.thestill.cosmetic.ThemeService themeService;

    /**
     * {@code /still cosmetic <who>} — <b>an instrument, written because a session was
     * spent guessing.</b>
     *
     * <p>Three cosmetic symptoms were reported at once ("previews are broken, nothing
     * changes in tab or on the board or above my head, and picking a colour does
     * nothing") and not one of them could be told apart from the others without a client
     * in front of the server. Two of the three obvious hypotheses — the perk gate is
     * refusing, and the client-theme dial is on — were both false, and the only way to
     * know that was to read the live profile out of SQLite by hand.
     *
     * <p>So this prints the whole chain in one go: what is stored, what the perks allow,
     * what the theme resolver picked, and <b>what each of the four surfaces actually
     * renders</b>. A surface that disagrees with the stored value is a render bug; a
     * stored value that disagrees with the click is a write bug; and until this existed
     * there was no way to say which. It is the same argument as
     * {@code /still economy curve} — instruments instead of intuition.
     */
    private LiteralArgumentBuilder<CommandSourceStack> cosmeticBranch() {
        return Commands.literal("cosmetic")
                .then(Commands.argument("who", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            plugin.getServer().getOnlinePlayers()
                                    .forEach(p -> builder.suggest(p.getName()));
                            return builder.buildFuture();
                        })
                        .executes(context -> cosmeticReport(context,
                                StringArgumentType.getString(context, "who"))));
    }

    private int cosmeticReport(CommandContext<CommandSourceStack> context, String who) {
        CommandSender sender = context.getSource().getSender();
        Player target = plugin.getServer().getPlayerExact(who);
        Profile profile = target == null ? null : profiles.get(target.getUniqueId());
        if (profile == null) {
            Msg.error(sender, "No player online called '" + who + "'.");
            return 0;
        }
        if (themeService == null) {
            Msg.error(sender, "Cosmetics are not loaded.");
            return 0;
        }
        Msg.ok(sender, target.getName() + " — cosmetics");

        StringBuilder perks = new StringBuilder();
        for (String perk : com.thestill.role.Perks.DISPLAY_ORDER) {
            perks.append(themeService.has(profile, perk) ? "+" : "-").append(perk).append(' ');
        }
        Msg.raw(sender, Msg.field("perks", perks.toString().trim()));

        Msg.raw(sender, Msg.field("nick", "text='"
                + profile.state().get(com.thestill.cosmetic.CosmeticKeys.NICK_TEXT)
                + "' start='" + profile.state().get(com.thestill.cosmetic.CosmeticKeys.NICK_START)
                + "' end='" + profile.state().get(com.thestill.cosmetic.CosmeticKeys.NICK_END)
                + "' gradient="
                + profile.state().get(com.thestill.cosmetic.CosmeticKeys.NICK_GRADIENT)));
        Msg.raw(sender, Msg.field("chat", "colour='"
                + profile.state().get(com.thestill.cosmetic.CosmeticKeys.CHAT_COLOUR)
                + "' theme='" + profile.state().get(com.thestill.cosmetic.CosmeticKeys.CHAT_THEME)
                + "' on=" + profile.state().get(com.thestill.cosmetic.CosmeticKeys.CHAT_THEME_ON)));
        Msg.raw(sender, Msg.field("tag", "theme='"
                + profile.state().get(com.thestill.cosmetic.CosmeticKeys.TAG_THEME)
                + "' on=" + profile.state().get(com.thestill.cosmetic.CosmeticKeys.TAG_THEME_ON)
                + " match-chat="
                + profile.state().get(com.thestill.cosmetic.CosmeticKeys.TAG_MATCH_CHAT)));

        for (com.thestill.cosmetic.Target which : com.thestill.cosmetic.Target.values()) {
            Msg.raw(sender, Msg.field(which.name().toLowerCase(java.util.Locale.ROOT),
                    "chosen=" + themeService.chosen(profile, which).id()
                            + "  live=" + themeService.live(profile, which).id()));
        }

        // The surfaces, each drawn by the very code that draws it in game — tab and the
        // sidebar share one composer, so printing it once is printing both.
        Msg.raw(sender, Component.text("  tab/board: ", NamedTextColor.DARK_GRAY)
                .append(nametags.fullName(profile, target.getName())));
        Msg.raw(sender, Component.text("  chat:     ", NamedTextColor.DARK_GRAY)
                .append(com.thestill.cosmetic.NickRender.chat(profile, "Hello!", themeService)));
        Msg.raw(sender, Msg.field("above head", "team colour "
                + com.thestill.ui.Gradients.nearestNamed(
                        com.thestill.cosmetic.NickRender.headColour(profile, themeService,
                                ranks.rankOf(profile).colour()))
                + "  (the words above a head are the username; only its colour can move)"));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /still lamp} — what the lamp is doing, and a lamp for testing.
     *
     * <p><b>{@code give} is not how players get one.</b> The lamp is earned in game (design
     * law: nothing that touches perception is for sale), and this exists so it can be looked
     * at without mining for it. {@code status} is the more useful half — it says which of the
     * two channels is live, and "personal" versus "everyone near you sees it" is a difference
     * you cannot spot by standing there alone on a dev server.
     */
    private LiteralArgumentBuilder<CommandSourceStack> lampBranch() {
        return Commands.literal("lamp")
                .executes(context -> lampStatus(context.getSource().getSender()))
                .then(Commands.literal("status")
                        .executes(context -> lampStatus(context.getSource().getSender())))
                .then(Commands.literal("give")
                        .executes(context -> giveLamp(context, null))
                        .then(Commands.argument("who", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    plugin.getServer().getOnlinePlayers()
                                            .forEach(p -> builder.suggest(p.getName()));
                                    return builder.buildFuture();
                                })
                                .executes(context -> giveLamp(context,
                                        StringArgumentType.getString(context, "who")))));
    }

    private int lampStatus(CommandSender sender) {
        if (fog == null) {
            Msg.error(sender, "The fog service did not start.");
            return 0;
        }
        Msg.ok(sender, "Fog lamp");
        fog.status().forEach(line -> Msg.info(sender, "  " + line));
        return Command.SINGLE_SUCCESS;
    }

    private int giveLamp(CommandContext<CommandSourceStack> context, String who) {
        CommandSender sender = context.getSource().getSender();
        if (fog == null) {
            Msg.error(sender, "The fog service did not start.");
            return 0;
        }
        Player target = who == null
                ? (sender instanceof Player self ? self : null)
                : plugin.getServer().getPlayerExact(who);
        if (target == null) {
            Msg.error(sender, who == null
                    ? "Name a player — the console has no hands."
                    : "No player online called '" + who + "'.");
            return 0;
        }
        // Dropped at their feet if there is no room, rather than silently eaten. A lamp
        // that vanishes into a full inventory is indistinguishable from a lamp that was
        // never given, and this command exists precisely to test whether it works.
        target.getInventory().addItem(fog.newLamp()).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        Msg.ok(sender, target.getName() + " has a Fog Lamp. Main hand, below the fog line.");
        return Command.SINGLE_SUCCESS;
    }

    // ---- /still role ----------------------------------------------------

    /**
     * {@code /still role} — grant, clear and inspect the ranks people PAY for.
     *
     * <p>Separate from the D1–D26 ladder on purpose, and the separation is the point:
     * one axis is earned by mining and gates where you can go, the other is bought and
     * changes nothing but your tag. Nothing in this command can touch the first one.
     */
    private LiteralArgumentBuilder<CommandSourceStack> roleBranch() {
        return Commands.literal("role")
                .then(Commands.literal("list").executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    Msg.ok(sender, "Roles");
                    for (String category : roles.config().categories()) {
                        for (var role : roles.config().inCategory(category)) {
                            Msg.raw(sender, Component.text("  " + category + "  ",
                                            NamedTextColor.DARK_GRAY)
                                    .append(roles.tag(role))
                                    .append(Component.text("  " + role.id()
                                                    + "  w" + role.weight()
                                                    + "  perks: " + role.perks().size(),
                                            NamedTextColor.DARK_GRAY)));
                        }
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("info")
                        .then(Commands.argument("who", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    plugin.getServer().getOnlinePlayers()
                                            .forEach(p -> builder.suggest(p.getName()));
                                    return builder.buildFuture();
                                })
                                .executes(context -> roleInfo(context,
                                        StringArgumentType.getString(context, "who")))))
                .then(Commands.literal("set")
                        .then(Commands.argument("who", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    plugin.getServer().getOnlinePlayers()
                                            .forEach(p -> builder.suggest(p.getName()));
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("role", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            roles.config().all().keySet()
                                                    .forEach(builder::suggest);
                                            builder.suggest("clear");
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> setRole(context,
                                                StringArgumentType.getString(context, "who"),
                                                StringArgumentType.getString(context, "role"))))));
    }

    private int roleInfo(CommandContext<CommandSourceStack> context, String who) {
        CommandSender sender = context.getSource().getSender();
        Player target = plugin.getServer().getPlayerExact(who);
        Profile profile = target == null ? null : profiles.get(target.getUniqueId());
        if (profile == null) {
            Msg.error(sender, "No player online called '" + who + "'.");
            return 0;
        }
        Msg.ok(sender, target.getName());

        Msg.raw(sender, Component.text("  ", NamedTextColor.DARK_GRAY)
                .append(roles.prefix(profile))
                .append(Component.text(target.getName(), NamedTextColor.WHITE)));
        Msg.raw(sender, Msg.field("tab priority", String.valueOf(roles.tabPriority(profile))));
        Msg.raw(sender, Msg.field("ladder", ranks.rankOf(profile).display()
                + "  (a different axis — earned, not bought)"));
        return Command.SINGLE_SUCCESS;
    }

    private int setRole(CommandContext<CommandSourceStack> context, String who, String roleId) {
        CommandSender sender = context.getSource().getSender();
        Player target = plugin.getServer().getPlayerExact(who);
        Profile profile = target == null ? null : profiles.get(target.getUniqueId());
        if (profile == null) {
            Msg.error(sender, "No player online called '" + who + "'.");
            return 0;
        }
        if ("clear".equalsIgnoreCase(roleId) || "none".equalsIgnoreCase(roleId)) {
            // Clearing every category, explicitly. The sentinel is what stops the
            // default quietly coming back on the next read.
            for (String category : roles.config().categories()) {
                roles.assign(profile, category, null);
            }
            profiles.saveDurable(profile);
            roles.applyPermissions(target, profile);
            nametags.refresh(target);
            Msg.ok(sender, target.getName() + " has no roles now.");
            return Command.SINGLE_SUCCESS;
        }
        var role = roles.config().role(roleId);
        if (role == null) {
            Msg.error(sender, "No role called '" + roleId + "'. /still role list.");
            return 0;
        }
        roles.assign(profile, role.category(), role.id());
        profiles.saveDurable(profile);
        roles.applyPermissions(target, profile);
        // The team NAME encodes sort order, so a role change has to rebuild the team
        // rather than recolour it — same reason a rank-up does.
        nametags.refresh(target);
        Msg.ok(sender, target.getName() + " is now " + role.name()
                + " (" + role.category() + ").");
        return Command.SINGLE_SUCCESS;
    }

    // ---- /still verify --------------------------------------------------

    /**
     * {@code /still verify rig} — proves the rig, in the world, on foot.
     *
     * <p>This exists because the rig was rejected six times and the faults were always
     * the same shape: stairs that went nowhere, floors that did not connect, doors
     * with nothing in front of them. Every one of those was something the builder
     * reported as placed. A block counter cannot see a route; a flood fill can.
     *
     * <p>{@code RigLayoutTest} proves the same things about the <em>plan</em> in a
     * second, with no server. This proves the <em>build</em>, which is a different
     * claim — a pass drawn later can quietly overwrite a correct plate, and the only
     * way to know is to re-read the world.
     */
    private LiteralArgumentBuilder<CommandSourceStack> verifyBranch() {
        return Commands.literal("verify")
                .then(Commands.literal("rig").executes(this::verifyRig))
                .then(Commands.literal("opening").executes(this::verifyOpening));
    }

    /**
     * {@code /still verify opening} — can a brand-new player reach the winch?
     *
     * <p>The single most important route in the game since the spawn moved off the
     * rig and onto the Landing. A player wakes up in the fog on D1 and the only way
     * out is a lever thirty blocks away over ground that is generated with three
     * octaves of noise. If that walk does not exist, nothing else about the server
     * matters, and it is not a walk any rig check covers.
     */
    private int verifyOpening(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        World world = stillWorld.world();
        if (world == null) {
            Msg.error(sender, "thestill:depths is not loaded — nothing to verify.");
            return 0;
        }
        Location spawn = com.thestill.world.StillWorld.landingSpawn(world);
        Msg.raw(sender, Msg.field("spawn", spawn.getBlockX() + ", " + spawn.getBlockY()
                + ", " + spawn.getBlockZ()));

        // BOTH branches of the fork. Checking only the winch would pass a build where
        // the spur to the ore stopped in a hillside.
        boolean allGood = true;
        for (var target : List.of(com.thestill.world.StillWorld.landingQuarryTarget(world),
                com.thestill.world.StillWorld.landingTarget())) {
            allGood &= walkTo(sender, world, spawn, target);
        }
        if (!allGood) {
            Msg.error(sender, "The opening is broken.");
            return 0;
        }
        Msg.ok(sender, "A new player can walk from the Landing to the ore, and out by the winch.");
        return Command.SINGLE_SUCCESS;
    }

    private boolean walkTo(CommandSender sender, World world, Location spawn,
                           com.thestill.world.StillWorld.Target target) {
        Location to = new Location(world, target.x(), target.y(), target.z());

        // canWalk, not flood — it stops the moment it arrives.
        //
        // The rig check floods once and asks fifty-six questions of the answer, which
        // is the right shape there. Here there is exactly one question, and the
        // Landing is a 512-wide plain of which every square is standable: flooding it
        // walked 187,000 nodes on the main thread and cost three seconds of TPS to
        // learn something an early exit finds in a fraction of that.
        long started = System.nanoTime();
        var result = com.thestill.world.ClimbVerifier.canWalk(world, spawn, to, 260,
                target.slack());
        long millis = (System.nanoTime() - started) / 1_000_000L;

        if (result.reachable()) {
            Msg.raw(sender, Msg.field(target.what(),
                    "reached, " + result.visited() + " block(s) in " + millis + "ms"));
            return true;
        }
        Msg.error(sender, target.what() + " at " + target.x() + ", " + target.y() + ", "
                + target.z() + " CANNOT be walked to from spawn — " + result.detail());
        return false;
    }

    private int verifyRig(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        World world = stillWorld.world();
        if (world == null) {
            Msg.error(sender, "thestill:depths is not loaded — nothing to verify.");
            return 0;
        }
        if (stillWorld.isBuilding()) {
            Msg.error(sender, "Still building — " + stillWorld.remaining()
                    + " block(s) to go. Verifying half a rig proves nothing.");
            return 0;
        }
        List<String> missing = com.thestill.world.StillWorld.unplacedModules();
        if (!missing.isEmpty()) {
            Msg.warn(sender, "The layout could not place: " + String.join(", ", missing));
        }

        long started = System.nanoTime();
        var reach = com.thestill.world.ClimbVerifier.flood(
                world, DepthPlan.rigSpawn(world), 150);
        List<com.thestill.world.StillWorld.Target> targets =
                com.thestill.world.StillWorld.rigTargets();

        List<String> failures = new java.util.ArrayList<>();
        for (var target : targets) {
            if (!reach.reaches(target.x(), target.y(), target.z(), target.slack())) {
                failures.add(target.what() + " (" + target.x() + "," + target.y()
                        + "," + target.z() + ")");
            }
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;

        Msg.raw(sender, Msg.field("walked", reach.size() + " standable block(s) in "
                + millis + "ms"));
        Msg.raw(sender, Msg.field("checked", targets.size() + " door(s), stair end(s) "
                + "and floor point(s)"));
        if (reach.exhausted()) {
            Msg.warn(sender, "The walk hit its node cap — treat every failure below as "
                    + "NOT PROVEN rather than as broken.");
        }
        if (failures.isEmpty()) {
            Msg.ok(sender, "Every one of them is reachable on foot from spawn.");
            return Command.SINGLE_SUCCESS;
        }
        Msg.error(sender, failures.size() + " of " + targets.size()
                + " cannot be walked to:");
        for (String failure : failures.subList(0, Math.min(20, failures.size()))) {
            Msg.info(sender, "  " + failure);
        }
        if (failures.size() > 20) {
            Msg.info(sender, "  ...and " + (failures.size() - 20) + " more.");
        }
        return 0;
    }

    // ---- /still setup ---------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> setupBranch() {
        return Commands.literal("setup")
                .then(Commands.literal("world").executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    long queued = stillWorld.planEverything(DepthPlan.DEPTH_COUNT);
                    if (queued < 0) {
                        Msg.error(sender, "thestill:depths is not loaded — check the datapack.");
                        return 0;
                    }
                    Msg.ok(sender, "Building the rig deck, the plains, and "
                            + (DepthPlan.DEPTH_COUNT - 1) + " descent caves — "
                            + String.format("%,d", queued) + " blocks.");
                    Msg.info(sender, "Runs in the background under a per-tick budget. "
                            + "/still setup status to watch it.");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("status").executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    if (stillWorld.isBuilding()) {
                        Msg.ok(sender, String.format("Building — %,d placed, %,d to go.",
                                stillWorld.placed(), stillWorld.remaining()));
                    } else {
                        Msg.ok(sender, String.format("Idle. %,d block(s) placed last run.",
                                stillWorld.placed()));
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("fog")
                        .then(Commands.literal("clear").executes(context ->
                                fog(context, true)))
                        .then(Commands.literal("thick").executes(context ->
                                fog(context, false))))
                .then(Commands.literal("biomes").executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    // Seconds, not minutes — the fog biomes can be iterated on
                    // without rebuilding twenty million blocks.
                    Msg.ok(sender, stillWorld.paintBiomesNow(DepthPlan.DEPTH_COUNT));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("depths")
                        .executes(context -> setupDepths(context, DepthPlan.DEPTH_COUNT))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(context -> setupDepths(context,
                                        IntegerArgumentType.getInteger(context, "count")))));
    }

    /**
     * {@code /fog} — one word, toggles.
     *
     * <p>This started life as {@code /still setup fog clear}, which is four words
     * deep inside an admin tree and might as well not have existed. A tool an admin
     * reaches for constantly gets a top-level name.
     */
    public LiteralCommandNode<CommandSourceStack> buildFogCommand() {
        return Commands.literal("fog")
                .requires(source -> source.getSender().hasPermission("thestill.admin"))
                .executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    if (!(sender instanceof org.bukkit.entity.Player player)) {
                        Msg.error(sender, "Players only — it works where you stand.");
                        return 0;
                    }
                    boolean clear = fogCleared.add(player.getUniqueId());
                    if (!clear) {
                        fogCleared.remove(player.getUniqueId());
                    }
                    Msg.ok(sender, stillWorld.fogAround(player, clear));
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("clear").executes(context -> fog(context, true)))
                .then(Commands.literal("thick").executes(context -> fog(context, false)))
                .build();
    }

    /** Who has the fog off, so a bare {@code /fog} knows which way to flip. */
    private final java.util.Set<java.util.UUID> fogCleared = new java.util.HashSet<>();

    /** {@code /still setup fog clear|thick} — see it without the weather in the way. */
    private int fog(CommandContext<CommandSourceStack> context, boolean clear) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            Msg.error(sender, "Players only — it works on where you are standing.");
            return 0;
        }
        Msg.ok(sender, stillWorld.fogAround(player, clear));
        return Command.SINGLE_SUCCESS;
    }

    private int setupDepths(CommandContext<CommandSourceStack> context, int count) {
        CommandSender sender = context.getSource().getSender();
        // Always the Still's own dimension, never wherever the sender happens to
        // be standing. The depths only make sense in a world 3,840 blocks tall.
        World world = stillWorld.world();
        if (world == null) {
            Msg.error(sender, "thestill:depths is not loaded — check the datapack is enabled, "
                    + "then restart.");
            return 0;
        }
        DepthSetup.Result result = depths.createDepths(world, count);
        Msg.ok(sender, "Created " + result.created() + " depth(s)"
                + (result.skipped() > 0 ? ", " + result.skipped() + " already existed" : "")
                + " in " + world.getName() + ".");
        if (!result.failed().isEmpty()) {
            Msg.error(sender, "Failed: " + result.failed());
        }
        if (result.created() > 0) {
            Msg.info(sender, "They fill themselves on the next reset check "
                    + "(a few seconds). Run /still setup world, then step off the rig.");
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---- /still mine ----------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> mineBranch() {
        return Commands.literal("mine")
                .then(Commands.literal("status").executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    var all = mines.mines();
                    Msg.ok(sender, all.size() + " mine(s)");
                    all.keySet().stream().sorted().forEach(id -> {
                        Region region = regions.index().byId(id);
                        if (region == null) {
                            return;
                        }
                        String state = mines.isResetting(id)
                                ? "refilling " + Math.round(mines.resetProgress(id) * 100) + "%"
                                : Math.round((1 - mines.minedFraction(region)) * 100) + "% ore left";
                        Msg.raw(sender, Msg.field(id, state));
                    });
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("reset")
                        .then(regionIdArgument()
                                .executes(context -> {
                                    CommandSender sender = context.getSource().getSender();
                                    String id = StringArgumentType.getString(context, "id");
                                    Region region = regions.index().byId(id);
                                    if (region == null) {
                                        Msg.error(sender, "No region '" + id + "'.");
                                        return 0;
                                    }
                                    if (!mines.reset(region, "admin")) {
                                        Msg.error(sender, id + " is not a mine, or is already "
                                                + "refilling.");
                                        return 0;
                                    }
                                    Msg.ok(sender, "Refilling " + id + ".");
                                    return Command.SINGLE_SUCCESS;
                                })));
    }

    // ---- /still economy -------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> economyBranch() {
        return Commands.literal("economy")
                .then(Commands.literal("audit").executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    var problems = prices.findCraftPrinters();
                    if (problems.isEmpty()) {
                        Msg.ok(sender, "No craft exploits in " + prices.size() + " price(s).");
                    } else {
                        Msg.error(sender, problems.size() + " MONEY PRINTER(S):");
                        problems.forEach(problem -> Msg.raw(sender,
                                Component.text("  " + problem, NamedTextColor.RED)));
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("curve").executes(this::economyCurve))
                .then(Commands.literal("progression").executes(this::economyProgression));
    }

    /**
     * How long the ladder actually takes, in blocks and in hours.
     *
     * <p>The Phase 1 gate is "a brand-new player reaches D5 without asking a
     * question". Clarity is only half of that — if D5 is thirty hours away, the
     * gate cannot be run at all. This turns the ladder into the number that
     * decides it, instead of a table of costs nobody has multiplied out.
     */
    private int economyProgression(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        Msg.ok(sender, "Time to climb (mining the deepest depth you can reach)");

        long cumulativeSeconds = 0;
        long cumulativeCost = 0;
        for (int depth = 1; depth <= Math.min(8, DepthPlan.DEPTH_COUNT); depth++) {
            var ladder = ranks.ladder();
            if (depth >= ladder.size()) {
                break;
            }
            // Buying rank at index `depth` is what opens depth+1. But you cannot
            // earn in A plains depth, so the money for that rank comes from the deepest
            // mine you can actually reach.
            long cost = ranks.costOf(ladder.byIndex(depth));
            int mining = depth;
            double perBlock = DepthPlan.expectedValuePerBlock(mining, material -> {
                Long price = prices.materialPrice(material);
                return price == null ? 0L : price;
            });
            if (perBlock <= 0) {
                continue;
            }
            long blocks = Math.round(cost / perBlock);
            double seconds = blocks * secondsPerBlock(mining);
            cumulativeSeconds += Math.round(seconds);
            cumulativeCost += cost;

            Msg.raw(sender, Component.text(String.format("  D%d -> D%d  ", depth, depth + 1),
                            NamedTextColor.DARK_GRAY)
                    .append(Component.text(Money.format(cost), NamedTextColor.WHITE))
                    .append(Component.text(String.format("  %,d blocks  ", blocks),
                            NamedTextColor.DARK_GRAY))
                    .append(Component.text(formatDuration(Math.round(seconds)),
                            NamedTextColor.AQUA))
                    .append(Component.text("   total " + formatDuration(cumulativeSeconds),
                            NamedTextColor.DARK_GRAY)));
        }
        Msg.info(sender, "Reaching D5 costs " + Money.format(cumulativeCostToD5())
                + " of mining, with no enchants and no other income.");
        return Command.SINGLE_SUCCESS;
    }

    private long cumulativeCostToD5() {
        long total = 0;
        for (int i = 1; i <= 4 && i < ranks.ladder().size(); i++) {
            total += ranks.costOf(ranks.ladder().byIndex(i));
        }
        return total;
    }

    /**
     * Seconds to break one block at this depth with the starting iron pickaxe.
     *
     * <p>Vanilla formula, correct tool: {@code hardness / toolSpeed * 1.5}. Iron
     * is speed 6. Filler dominates because it is 75% of the mine.
     */
    private static double secondsPerBlock(int depth) {
        double fillerHardness = depth <= 12 ? 2.0 : 1.8; // stone family vs concrete
        double oreHardness = 4.5;                        // deepslate ore variants
        return 0.75 * (fillerHardness / 6.0 * 1.5) + 0.25 * (oreHardness / 6.0 * 1.5);
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    /**
     * Expected money per block broken, per depth.
     *
     * <p>The instrument the build plan keeps asking for — "read the logs, not
     * your intuition". Without it, tuning an ore weight is guesswork, because
     * the multi-drop ores (copper 2-5, redstone 4-5, lapis 4-9) mean a weight of
     * 5 can be worth more than a weight of 12.
     */
    private int economyCurve(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        Msg.ok(sender, "Expected value per block broken");
        long previous = 0;
        int dips = 0;
        for (int depth = 1; depth <= DepthPlan.DEPTH_COUNT; depth++) {
            long minor = Math.round(DepthPlan.expectedValuePerBlock(depth, material -> {
                Long price = prices.materialPrice(material);
                return price == null ? 0L : price;
            }));
            boolean dip = previous > 0 && minor < previous;
            if (dip) {
                dips++;
            }
            String delta = previous == 0 ? ""
                    : String.format("  %+.0f%%", (minor - previous) * 100.0 / previous);
            Msg.raw(sender, Component.text("  D" + depth + ": ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(Money.format(minor) + delta,
                            dip ? NamedTextColor.RED : NamedTextColor.WHITE))
                    .append(dip
                            ? Component.text("  <- PAYS LESS THAN THE ONE ABOVE",
                            NamedTextColor.RED)
                            : Component.empty()));
            previous = minor;
        }
        if (dips > 0) {
            // A depth that pays less than the one above it means a player bought
            // a rank and got poorer. Always a bug, never a design choice.
            Msg.error(sender, dips + " depth(s) pay less than the depth above them.");
        } else {
            Msg.ok(sender, "Monotonic — every depth pays more than the last.");
        }
        return Command.SINGLE_SUCCESS;
    }

    /** The item vault's dependencies, or null until {@link #withItems} runs. */
    private com.thestill.ui.config.ItemVault vault;

    /**
     * Late-wired, same reason as {@link #withFog}: the audit trail is constructed at
     * the very end of the service phase, after commands exist.
     *
     * <p>The command is handed the <em>registry</em>, never a list of what it thinks
     * the plugin can build. That is the whole law — see
     * {@link com.thestill.item.ItemRegistry}.
     */
    public void withItems(com.thestill.item.ItemRegistry items,
                          com.thestill.admin.AdminAudit audit) {
        this.vault = new com.thestill.ui.config.ItemVault(items, profiles, audit);
    }

    /** {@code /config} — the dial GUI and the item vault, both generated from a registry. */
    public LiteralCommandNode<CommandSourceStack> buildConfigCommand() {
        return Commands.literal("config")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> {
                    if (!(context.getSource().getSender() instanceof Player player)) {
                        Msg.error(context.getSource().getSender(),
                                "Players only. From console use /still dial list and /still dial set.");
                        return 0;
                    }
                    new com.thestill.ui.config.ConfigMenu(plugin, player, dials, vault).open();
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
