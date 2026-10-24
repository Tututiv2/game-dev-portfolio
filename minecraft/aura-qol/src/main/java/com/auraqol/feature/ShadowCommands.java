package com.auraqol.feature;

import java.util.Locale;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * {@code /sp} - the Shadow Prison live panels.
 *
 * <ul>
 *   <li>{@code /sp} - what the live channel is doing and what it has received</li>
 *   <li>{@code /sp reset} - start the session rates over</li>
 *   <li>{@code /sp mode [session|recent]}, {@code /sp window <1|5|10|30>}</li>
 *   <li>{@code /sp profile <name|uuid>} - a profile card, answered by the server</li>
 *   <li>{@code /sp close} - hide the profile card</li>
 * </ul>
 *
 * <p>A CLIENT command, answered before anything is sent; Shadow Prison has no
 * {@code /sp} of its own (checked against its plugin.yml and every
 * {@code getCommand}). The only one that talks to the server is
 * {@code profile}, through the rate-limited REQUEST on the live channel.
 */
final class ShadowCommands {

    private ShadowCommands() {
    }

    static LiteralArgumentBuilder<FabricClientCommandSource> sp() {
        return literal("sp")
                .executes(ctx -> {
                    head(ctx.getSource(), "Shadow Live");
                    for (String l : ShadowLive.status()) {
                        say(ctx.getSource(), l);
                    }
                    say(ctx.getSource(), "  /sp reset | mode [session|recent] | window <1|5|10|30> | profile <name> | close");
                    say(ctx.getSource(), "  Panels: /aq toggle sp-mining | sp-earnings | sp-rankup | sp-boosts | sp-contract"
                            + " | sp-skills | sp-profile. Move them with ].");
                    return 1;
                })
                .then(literal("reset").executes(ctx -> {
                    ShadowLive.resetSession();
                    say(ctx.getSource(), "Shadow session reset (rates and session totals).");
                    return 1;
                }))
                .then(literal("mode")
                        .executes(ctx -> {
                            HudConfig c = HudConfig.get();
                            c.spRecent = !c.spRecent;
                            c.save();
                            say(ctx.getSource(), "Shadow rates: " + ShadowLive.modeLabel() + ".");
                            return 1;
                        })
                        .then(literal("session").executes(ctx -> {
                            HudConfig c = HudConfig.get();
                            c.spRecent = false;
                            c.save();
                            say(ctx.getSource(), "Shadow rates: session average (all active time this session).");
                            return 1;
                        }))
                        .then(literal("recent").executes(ctx -> {
                            HudConfig c = HudConfig.get();
                            c.spRecent = true;
                            c.save();
                            say(ctx.getSource(), "Shadow rates: " + ShadowLive.modeLabel() + " of active play.");
                            return 1;
                        })))
                .then(literal("window").then(argument("minutes", IntegerArgumentType.integer(1, 60))
                        .suggests((ctx, b) -> {
                            for (int w : HudConfig.MONEY_WINDOWS) {
                                b.suggest(w);
                            }
                            return b.buildFuture();
                        })
                        .executes(ctx -> {
                            HudConfig c = HudConfig.get();
                            c.spWindowMinutes = HudConfig.snapWindow(IntegerArgumentType.getInteger(ctx, "minutes"));
                            c.spRecent = true;
                            c.save();
                            say(ctx.getSource(), "Shadow rates: " + ShadowLive.modeLabel()
                                    + " of active play (1, 5, 10 or 30).");
                            return 1;
                        })))
                .then(literal("profile").then(argument("player", StringArgumentType.word())
                        .suggests((ctx, b) -> {
                            String typed = b.getRemaining().toLowerCase(Locale.ROOT);
                            for (String n : ShadowLive.onlineNames()) {
                                if (n.toLowerCase(Locale.ROOT).startsWith(typed)) {
                                    b.suggest(n);
                                }
                            }
                            return b.buildFuture();
                        })
                        .executes(ctx -> {
                            String who = StringArgumentType.getString(ctx, "player");
                            String why = ShadowLive.requestProfile(who);
                            if (why != null) {
                                err(ctx.getSource(), why);
                                return 0;
                            }
                            say(ctx.getSource(), "Looking up " + who + "...");
                            return 1;
                        })))
                .then(literal("close").executes(ctx -> {
                    ShadowLive.closeCard();
                    return 1;
                }));
    }

    private static void head(FabricClientCommandSource src, String text) {
        src.sendFeedback(Component.literal("[Aura] ").withColor(0xE0B01E)
                .append(Component.literal(text).withColor(0xFFFFFF)));
    }

    private static void say(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Component.literal(msg).withColor(0xC8C8C8));
    }

    private static void err(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Component.literal(msg).withColor(0xFF6B6B));
    }
}
