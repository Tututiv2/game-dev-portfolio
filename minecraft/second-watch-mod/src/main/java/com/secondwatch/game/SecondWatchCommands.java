package com.secondwatch.game;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.secondwatch.core.Utterance;
import net.minecraft.world.phys.Vec3;
import java.util.Random;
import com.secondwatch.core.Journal;
import com.secondwatch.core.Survivor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Operator commands.
 *
 * <p>These exist for building and for recording, not for players. A run that
 * needs a command to be frightening has already failed - but a five-night arc
 * that can only be reached by playing for five nights cannot be tested or
 * re-shot, so {@code night} and {@code status} earn their place.
 *
 * <p>{@code status} deliberately prints the run's real rules. Never run it on
 * stream.
 */
public final class SecondWatchCommands {

    private SecondWatchCommands() {
    }

    /**
     * Replay one of the caller's own recorded lines from a distance.
     *
     * <p>Exists because the phantom's voice is the hardest thing in the mod to
     * evaluate and the most expensive to reach - it needs two people, a run
     * deep enough for SOCIAL, and the split to fire. This answers the same
     * question in one command: does a borrowed voice, at range, in the dark,
     * sound like a person standing there.
     */
    private static int echo(CommandSourceStack source, int blocks) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        RunState run = Runs.get();
        ServerPlayer player = source.getPlayerOrException();

        if (run == null || !run.started()) {
            source.sendFailure(Component.literal("no run in progress"));
            return 0;
        }
        if (!Voice.ready()) {
            source.sendFailure(Component.literal(
                    "voice chat is not connected - nothing has been recorded"));
            return 0;
        }

        String name = player.getName().getString();
        List<Utterance> mine = run.corpus().all(name);
        if (mine.isEmpty()) {
            source.sendFailure(Component.literal(
                    "it has nothing of you yet. say something first, then try again."));
            return 0;
        }

        // Newest first: while testing you want to hear the thing you just said,
        // not something from twenty minutes ago you no longer remember.
        Utterance line = mine.get(mine.size() - 1);

        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1.0e-6) flat = new Vec3(1, 0, 0);
        flat = flat.normalize();

        double x = player.getX() + flat.x * blocks;
        double y = player.getY() + 1.2;
        double z = player.getZ() + flat.z * blocks;

        Voice.speakLine(player, line, x, y, z, false, false, false);
        source.sendSuccess(() -> Component.literal(
                "§7...from " + blocks + " blocks that way."), false);
        return 1;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("secondwatch")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))

                .then(Commands.literal("start").executes(ctx -> {
                    RunState run = Runs.get();
                    if (run == null) {
                        ctx.getSource().sendFailure(Component.literal("no world loaded"));
                        return 0;
                    }
                    if (run.started()) {
                        ctx.getSource().sendFailure(Component.literal("this run has already begun"));
                        return 0;
                    }
                    run.begin(System.currentTimeMillis());
                    run.save(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("night 1. nothing is wrong yet."), true);
                    return 1;
                }))

                .then(Commands.literal("status").executes(ctx -> {
                    RunState run = Runs.get();
                    if (run == null) {
                        ctx.getSource().sendFailure(Component.literal("no run"));
                        return 0;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("seed ").append(run.seed())
                            .append(" | night ").append(run.night()).append('/').append(run.rules().nights())
                            .append(" | ").append(run.dread().tier())
                            .append(String.format(" (%.2f)", run.dread().value()))
                            .append("\ntell ").append(run.rules().tell())
                            .append(" | trigger ").append(run.rules().trigger())
                            .append("\nvoice corpus ").append(run.corpus().size()).append(" lines")
                            .append("\nsun gone: ").append(run.sunIsGone())
                            .append("\nbefore you:");
                    for (Survivor s : run.group().members()) {
                        sb.append("\n  ").append(s.name()).append(" - ").append(s.fate().phrase());
                        if (!s.gotOut()) sb.append(" (night ").append(s.nightLost()).append(')');
                    }
                    String out = sb.toString();
                    ctx.getSource().sendSuccess(() -> Component.literal(out), false);
                    return 1;
                }))

                .then(Commands.literal("book").executes(ctx -> {
                    RunState run = Runs.get();
                    if (run == null) {
                        ctx.getSource().sendFailure(Component.literal("no run"));
                        return 0;
                    }
                    ServerPlayer player = ctx.getSource().getPlayerOrException();

                    // Built against whoever is actually online, because the
                    // prescient entry has to name somebody who is really here.
                    List<String> names = RunState.namesOf(ctx.getSource().getServer());
                    if (names.isEmpty()) names = List.of(player.getName().getString());

                    Journal journal = run.journalFor(names);
                    ItemStack book = JournalBook.create(journal, run.group().diarist().name());
                    if (!player.addItem(book)) {
                        player.drop(book, false);
                    }
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("somebody left this here."), false);
                    return 1;
                }))

                .then(Commands.literal("night").executes(ctx -> {
                    RunState run = Runs.get();
                    if (run == null || !run.started()) {
                        ctx.getSource().sendFailure(Component.literal("no run in progress"));
                        return 0;
                    }
                    run.forceNextNight(System.currentTimeMillis());
                    run.save(ctx.getSource().getServer());
                    NightKeeper.enforce(ctx.getSource().getServer(), run);
                    int n = run.night();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("night " + n + ". " + run.dread().tier()), true);
                    return 1;
                }))

                .then(Commands.literal("phantom")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            com.secondwatch.SecondWatch.PHANTOMS.spawnTest(player);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(
                                            "look up. (test puppet, 60s, only you can see it)"),
                                    false);
                            return 1;
                        })
                        .then(Commands.argument("skin", StringArgumentType.word()).executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String wanted = StringArgumentType.getString(ctx, "skin");
                            var server = ctx.getSource().getServer();

                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("§7looking up " + wanted + "..."), false);

                            // The lookup is network IO and must not block the
                            // server thread; hop back before touching the world.
                            SkinLookup.resolve(wanted).thenAccept(profile -> server.execute(() -> {
                                if (profile == null) {
                                    player.sendSystemMessage(Component.literal(
                                            "§cNo Minecraft account called '" + wanted + "'."));
                                    return;
                                }
                                com.secondwatch.SecondWatch.PHANTOMS.spawnTest(player, profile);
                                player.sendSystemMessage(Component.literal(
                                        "§7It is wearing " + profile.name() + "."));
                            }));
                            return 1;
                        })))

                .then(Commands.literal("voice").executes(ctx -> {
                    RunState run = Runs.get();
                    String msg = "voice chat installed: " + Voice.present()
                            + " | bridge ready: " + Voice.ready()
                            + " | corpus: " + (run == null ? 0 : run.corpus().size()) + " lines";
                    ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                    return 1;
                }))

                // Hear yourself from over there. The single most useful thing
                // for judging the phantom without a second person: the whole
                // effect lives in how a borrowed voice sits in the distance.
                .then(Commands.literal("echo")
                        .executes(ctx -> echo(ctx.getSource(), 30))
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(0, 200))
                                .executes(ctx -> echo(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "blocks")))))

                .then(Commands.literal("seat").executes(ctx -> {
                    RunState run = Runs.get();
                    if (run == null) {
                        ctx.getSource().sendFailure(Component.literal("no run"));
                        return 0;
                    }
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    player.getInventory().add(
                            new ItemStack(com.secondwatch.content.SecondWatchContent.THE_SEAT));
                    String state = run.seat().spent()
                            ? "already used by " + run.seat().departed().orElse("?")
                            : run.seat().inProgress() ? "somebody is in it" : "unused";
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("the way out (" + state + ")"), false);
                    return 1;
                }))

                .then(Commands.literal("release").executes(ctx -> {
                    NightKeeper.release(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("the sky is moving again."), true);
                    return 1;
                }))
        );
    }
}
