package com.auracapes;

import java.util.Map;
import java.util.UUID;

import com.auracapes.compat.Compat;
import com.auracapes.mods.ModToggles;
import com.auracapes.ui.AccountScreen;
import com.auracapes.ui.AuraButton;
import com.auracapes.ui.ModsScreen;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// The helper class is ClientCommands, not ClientCommandManager - Fabric API
// renamed it, and the old name is what every tutorial online still shows.
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * AuraCapes - a cape mod with no website behind it.
 *
 * <p>Client-side only. It registers one command and one mixin, keeps its
 * state in a JSON file in the config folder, and never opens a socket.
 */
public final class AuraCapes implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("auracapes");

    /**
     * G, because C and V are taken on most setups and G is free in vanilla.
     * Rebindable in Options -> Controls like any other key.
     */
    private static KeyMapping libraryKey;

    @Override
    public void onInitializeClient() {
        CapeRegistry.load();
        CapeSync.start(CapeRegistry.syncUrls());
        FreezeCatcher.start();
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, access) -> dispatcher.register(buildCommand()));
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, access) -> dispatcher.register(buildNameCommand()));
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, access) -> dispatcher.register(buildModsCommand()));
        // Jars switched off this session are still loaded (and locked by Windows); a helper renames
        // them once this process has exited. See ModToggles.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ModToggles.onGameClosing());

        libraryKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.auracapes.library", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G, KeyMapping.Category.MISC));

        // consumeClick() drains a queue of presses rather than reporting the
        // key's current state, which is what stops one long hold from opening
        // the screen on every tick for as long as the finger is down. It also
        // removes the need to check whether a screen is already open: presses
        // are only ever recorded while none is, so the queue is empty in the
        // one case a guard would have covered.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (libraryKey.consumeClick()) {
                CapeLibraryScreen.open();
            }
        });

        // Back on whichever account you quit on. CLIENT_STARTED rather than
        // here: during mod init the game has not finished building, and the
        // launch account has to be captured from a finished Minecraft.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> Accounts.applySaved());

        // Two buttons on the pause menu, top-left, out of vanilla's way.
        // Accounts is reachable in-world on purpose even though switching is
        // not: the screen says why, which beats a button that is missing.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
                Screens.getWidgets(screen).add(new AuraButton(6, 6, 70, 18,
                        Component.literal("Cosmetics"), AuraButton.Style.SECONDARY,
                        () -> Compat.setScreen(new CapeLibraryScreen(screen))));
                Screens.getWidgets(screen).add(new AuraButton(80, 6, 70, 18,
                        Component.literal("Accounts"), AuraButton.Style.SECONDARY,
                        () -> Compat.setScreen(new AccountScreen(screen))));
                Screens.getWidgets(screen).add(new AuraButton(154, 6, 70, 18,
                        Component.literal("Mods"), AuraButton.Style.SECONDARY,
                        () -> openModsLibrary(screen)));
            }
            Reconnect.addButton(screen);
        });

        LOG.info("[auracapes] ready - Aura menu on, G for cosmetics, /cape and /name");
    }

    /**
     * {@code /name} - the streamer alias.
     *
     * <p>A separate command rather than {@code /cape name} because it is a
     * separate thing: you reach for it when you are thinking about usernames,
     * not about capes, and burying it under /cape is where nobody would look.
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> buildNameCommand() {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("name");

        root.executes(ctx -> {
            if (StreamerName.enabled()) {
                say(ctx.getSource(), "Showing as \"" + StreamerName.alias()
                        + "\" - only on your screen.");
                say(ctx.getSource(), "/name off  to go back to "
                        + StreamerName.realName());
            } else {
                say(ctx.getSource(), "Streamer name is off. You are "
                        + StreamerName.realName() + ".");
                say(ctx.getSource(), "/name <anything>  to try one on.");
            }
            return 1;
        });

        root.then(literal("off").executes(ctx -> {
            StreamerName.off();
            say(ctx.getSource(), "Back to " + StreamerName.realName() + ".");
            return 1;
        }));

        root.then(argument("alias", StringArgumentType.greedyString()).executes(ctx -> {
            String alias = StringArgumentType.getString(ctx, "alias").trim();
            if (alias.isBlank()) {
                say(ctx.getSource(), "Give me a name to show.");
                return 0;
            }
            StreamerName.set(alias);
            say(ctx.getSource(), "Now showing as \"" + alias + "\".");
            say(ctx.getSource(), "Press F5 to see it over your head. Nobody else "
                    + "sees this - it is your screen only, and it claims nothing.");
            return 1;
        }));

        return root;
    }

    /**
     * {@code /mods} - switch mods and presets. Every change takes effect on the next start; the
     * reason (Windows holds a loaded jar open) is on {@link ModToggles}.
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> buildModsCommand() {
        // "/mods" is AuraQoL's feature library (instant toggles, Lunar style); whole jars live here.
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("modjars");
        SuggestionProvider<FabricClientCommandSource> modNames = (ctx, b) -> SharedSuggestionProvider.suggest(
                ModToggles.list().stream().map(v -> v.entry().id()), b);
        SuggestionProvider<FabricClientCommandSource> presetNames = (ctx, b) -> SharedSuggestionProvider.suggest(
                ModToggles.presets().keySet(), b);

        root.executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            mc.schedule(() -> Compat.setScreen(new ModsScreen(Compat.screen())));
            return 1;
        });

        root.then(literal("list").executes(ctx -> {
            for (ModToggles.ModView v : ModToggles.list()) {
                String state = v.onNextStart() ? "ON " : "OFF";
                say(ctx.getSource(), state + " " + v.entry().name() + " [" + v.entry().id() + "]");
            }
            say(ctx.getSource(), "Presets: " + String.join(", ", ModToggles.presets().keySet())
                    + "  ->  /modjars preset <name>");
            return 1;
        }));

        root.then(literal("on").then(argument("mod", StringArgumentType.greedyString()).suggests(modNames)
                .executes(ctx -> switchMod(ctx.getSource(), StringArgumentType.getString(ctx, "mod"), true))));
        root.then(literal("off").then(argument("mod", StringArgumentType.greedyString()).suggests(modNames)
                .executes(ctx -> switchMod(ctx.getSource(), StringArgumentType.getString(ctx, "mod"), false))));

        root.then(literal("preset").then(argument("name", StringArgumentType.word()).suggests(presetNames)
                .executes(ctx -> {
                    for (String line : ModToggles.applyPreset(StringArgumentType.getString(ctx, "name"))) {
                        say(ctx.getSource(), line);
                    }
                    return 1;
                })));
        return root;
    }

    /**
     * The pause-menu "Mods" button: AuraQoL's feature library when it is installed (reached by
     * reflection, so AuraCapes still works alone), otherwise this mod's whole-jar screen.
     */
    private static void openModsLibrary(net.minecraft.client.gui.screens.Screen parent) {
        try {
            Class.forName("com.auraqol.feature.ModsLibrary")
                    .getMethod("open", net.minecraft.client.gui.screens.Screen.class).invoke(null, parent);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            Compat.setScreen(new ModsScreen(parent));
        }
    }

    private static int switchMod(FabricClientCommandSource src, String query, boolean on) {
        var found = ModToggles.find(query);
        if (found.isEmpty()) {
            say(src, "No mod called '" + query + "'. /modjars list shows them all.");
            return 0;
        }
        for (String line : ModToggles.set(found.get().key(), on)) {
            say(src, line);
        }
        return 1;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildCommand() {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("cape");

        root.executes(ctx -> {
            say(ctx.getSource(), "Wearing: " + CapeRegistry.current());
            say(ctx.getSource(), "/cape list  to see them all");
            return 1;
        });

        // Brigadier matches literals before arguments, so this cannot be
        // shadowed by the /cape <name> branch further down - and there is no
        // cape called "library" anyway.
        root.then(literal("library").executes(ctx -> {
            Minecraft.getInstance().schedule(CapeLibraryScreen::open);
            return 1;
        }));

        root.then(literal("list").executes(ctx -> {
            say(ctx.getSource(), "Capes:");
            for (String n : CapeRegistry.names()) {
                String mark = n.equals(CapeRegistry.current()) ? "  <- wearing" : "";
                say(ctx.getSource(), "  " + n + " - " + CapeRegistry.describe(n) + mark);
            }
            return 1;
        }));

        // `/cape share` puts a READY COMMAND on the clipboard, not a UUID.
        // Telling somebody "find your UUID and paste it into a JSON file" is
        // where a good idea dies; handing them a line they paste into chat is
        // one action. The friend runs exactly what this prints.
        root.then(literal("share").executes(ctx -> {
            UUID id = CapeRegistry.localUuid();
            if (id == null) {
                say(ctx.getSource(), "Join a world first - I need your UUID.");
                return 0;
            }
            String line = "/cape friend add " + id + " " + CapeRegistry.current();
            Minecraft.getInstance().keyboardHandler.setClipboard(line);
            say(ctx.getSource(), "Copied to your clipboard. Send this to your friend:");
            say(ctx.getSource(), "  " + line);
            say(ctx.getSource(), "They paste it in chat and your cape shows straight away.");
            return 1;
        }));

        // Adding a friend takes effect on the NEXT FRAME - capeFor() reads the
        // map live, so there is nothing to restart. The old README said to
        // restart the game and that was simply wrong.
        LiteralArgumentBuilder<FabricClientCommandSource> friend = literal("friend");

        friend.then(literal("add")
                .then(argument("uuid", StringArgumentType.word())
                .then(argument("cape", StringArgumentType.word()).executes(ctx -> {
                    String rawId = StringArgumentType.getString(ctx, "uuid");
                    String cape = StringArgumentType.getString(ctx, "cape");
                    UUID id = CapeSync.uuid(rawId);
                    if (id == null) {
                        say(ctx.getSource(), "That is not a UUID: " + rawId);
                        return 0;
                    }
                    if (!CapeRegistry.isKnown(cape)) {
                        say(ctx.getSource(), "No cape called '" + cape
                                + "'. They may be on a newer build than you.");
                        return 0;
                    }
                    CapeRegistry.setOther(id, cape);
                    say(ctx.getSource(), "Added. They are wearing " + cape
                            + " - look at them now, no restart needed.");
                    return 1;
                }))));

        friend.then(literal("remove")
                .then(argument("uuid", StringArgumentType.word()).executes(ctx -> {
                    UUID id = CapeSync.uuid(StringArgumentType.getString(ctx, "uuid"));
                    if (id == null) {
                        say(ctx.getSource(), "That is not a UUID.");
                        return 0;
                    }
                    CapeRegistry.setOther(id, "none");
                    say(ctx.getSource(), "Removed.");
                    return 1;
                })));

        friend.then(literal("list").executes(ctx -> {
            Map<UUID, String> all = CapeRegistry.friends();
            if (all.isEmpty()) {
                say(ctx.getSource(), "No friends added yet. They run /cape share "
                        + "and send you the line.");
                return 1;
            }
            say(ctx.getSource(), all.size() + " friend cape(s):");
            for (Map.Entry<UUID, String> e : all.entrySet()) {
                say(ctx.getSource(), "  " + e.getKey() + "  " + e.getValue());
            }
            return 1;
        }));

        root.then(friend);

        root.then(argument("name", StringArgumentType.word()).executes(ctx -> {
            String name = StringArgumentType.getString(ctx, "name");
            if (!CapeRegistry.isKnown(name)) {
                say(ctx.getSource(), "No cape called '" + name + "'. Try /cape list");
                return 0;
            }
            CapeRegistry.wear(name);
            say(ctx.getSource(), name.equals("none")
                    ? "Cape off."
                    : "Now wearing " + name + ".");

            // Changing your cape does NOT reach anybody who already added
            // you - their client stored a name, not a subscription. Rather
            // than let that be discovered later by a friend saying "you
            // didn't change", put the new line straight on the clipboard and
            // say so, so re-sharing is a paste rather than a chore.
            UUID me = CapeRegistry.localUuid();
            if (me != null && !CapeRegistry.friends().isEmpty()) {
                String line = "/cape friend add " + me + " " + name;
                Minecraft.getInstance().keyboardHandler.setClipboard(line);
                say(ctx.getSource(), "Your friends still see your old one until "
                        + "they run the new line - it is on your clipboard now.");
            }
            return 1;
        }));

        return root;
    }

    private static void say(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Component.literal(msg));
    }
}
