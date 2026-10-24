package com.auraqol.feature;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.auraqol.skyblock.Fmt;
import com.auraqol.skyblock.PriceData;
import com.auraqol.skyblock.PriceService;
import com.auraqol.skyblock.TodoList;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Every chat command the SkyBlock features add.
 *
 * <ul>
 *   <li>{@code /aq} (alias {@code /auraqol}) - what is on, and {@code /aq toggle <feature>}</li>
 *   <li>{@code /aq prices} - where prices came from and how old; {@code refresh}</li>
 *   <li>{@code /todo ...} - the list</li>
 *   <li>{@code /money}, {@code /money reset|pause|resume|mode|window|breakdown|log|unpriced}</li>
 *   <li>{@code /aq sacks [clear]} - what the Sacks panel knows (NOT {@code /sacks}: that is Hypixel's)</li>
 *   <li>{@code /bps reset}</li>
 * </ul>
 *
 * <p>These are CLIENT commands: Fabric answers them before anything is sent,
 * so they never reach Hypixel and cannot trip its unknown-command reply. The
 * flip side is that a client command shadows a server command of the same
 * name - none of {@code todo}, {@code money}, {@code bps}, {@code aq} is a
 * Hypixel SkyBlock command as of writing; if one ever becomes one, rename here.
 */
public final class AuraCommands {

    /** /aq toggle names -> HUD layout ids (or "prices" / "npc" for the tooltip). */
    private static final Map<String, String> TOGGLES = Map.ofEntries(
            Map.entry("info", HudLayout.INFO),
            Map.entry("cps", HudLayout.CPS),
            Map.entry("keys", HudLayout.KEYS),
            Map.entry("zoom", "zoom"),
            Map.entry("nametag", "nametag"),
            Map.entry("hotkeys", "hotkeys"),
            Map.entry("bps", HudLayout.BPS),
            Map.entry("money", HudLayout.MONEY),
            Map.entry("todo", HudLayout.TODO),
            Map.entry("comms", HudLayout.COMMS),
            Map.entry("slayer", HudLayout.SLAYER),
            Map.entry("ability", HudLayout.ABILITY),
            Map.entry("sacks", HudLayout.SACKS),
            Map.entry("prices", "prices"),
            Map.entry("npc", "npc"),
            Map.entry("recipebuy", "recipebuy"),
            Map.entry("recipehint", "recipehint"),
            Map.entry(HudLayout.SP_MINING, HudLayout.SP_MINING),
            Map.entry(HudLayout.SP_EARNINGS, HudLayout.SP_EARNINGS),
            Map.entry(HudLayout.SP_RANKUP, HudLayout.SP_RANKUP),
            Map.entry(HudLayout.SP_BOOSTS, HudLayout.SP_BOOSTS),
            Map.entry(HudLayout.SP_CONTRACT, HudLayout.SP_CONTRACT),
            Map.entry(HudLayout.SP_SKILLS, HudLayout.SP_SKILLS),
            Map.entry(HudLayout.SP_PROFILE, HudLayout.SP_PROFILE));

    static final List<String> ORDER = List.of("info", "cps", "keys", "zoom", "nametag", "hotkeys", "bps", "money", "sacks", "todo",
            "comms", "slayer", "ability", "prices", "npc", "recipebuy", "recipehint",
            HudLayout.SP_MINING, HudLayout.SP_EARNINGS, HudLayout.SP_RANKUP, HudLayout.SP_BOOSTS,
            HudLayout.SP_CONTRACT, HudLayout.SP_SKILLS, HudLayout.SP_PROFILE);

    private AuraCommands() {
    }

    public static void register() {
        DevSelfTest.registerIfEnabled();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(aq("aq"));
            dispatcher.register(aq("auraqol"));
            dispatcher.register(todo());
            dispatcher.register(money());
            dispatcher.register(bps());
            dispatcher.register(ShadowCommands.sp());
            // The Lunar-style feature library. Every switch in it is instant.
            dispatcher.register(literal("mods").executes(ctx -> {
                ModsLibrary.openLater();
                return 1;
            }));
        });
    }

    // ------------------------------------------------------------ /aq

    private static LiteralArgumentBuilder<FabricClientCommandSource> aq(String name) {
        SuggestionProvider<FabricClientCommandSource> names = (ctx, b) -> {
            for (String n : ORDER) {
                if (n.startsWith(b.getRemainingLowerCase())) {
                    b.suggest(n);
                }
            }
            return b.buildFuture();
        };
        return literal(name)
                .executes(ctx -> {
                    head(ctx.getSource(), "AuraQoL - /aq toggle <name> switches one");
                    for (String n : ORDER) {
                        boolean on = isOn(n);
                        MutableComponent c = Component.literal("  " + (on ? "on   " : "off  ")).withColor(on ? 0x55FF55 : 0xFF5555);
                        c.append(Component.literal(n + "  ").withColor(0xFFFFFF));
                        c.append(Component.literal(describe(n)).withColor(0x8A8A8A));
                        ctx.getSource().sendFeedback(c);
                    }
                    say(ctx.getSource(), "  Move things: press ] (Edit HUD). Right-click a box there to hide/show it.");
                    return 1;
                })
                .then(literal("toggle").then(argument("feature", StringArgumentType.word()).suggests(names)
                        .executes(ctx -> {
                            String n = StringArgumentType.getString(ctx, "feature").toLowerCase(Locale.ROOT);
                            if (!TOGGLES.containsKey(n)) {
                                err(ctx.getSource(), "Unknown feature '" + n + "'. One of: " + String.join(", ", ORDER));
                                return 0;
                            }
                            boolean on = toggle(n);
                            say(ctx.getSource(), describe(n) + (on ? " ON" : " OFF"));
                            return 1;
                        })))
                .then(literal("sacks")
                        .executes(ctx -> {
                            head(ctx.getSource(), "Sacks");
                            for (String l : SacksHud.status()) {
                                say(ctx.getSource(), l);
                            }
                            say(ctx.getSource(), "  /aq sacks clear forgets every count.");
                            return 1;
                        })
                        .then(literal("clear").executes(ctx -> {
                            SacksHud.clear();
                            say(ctx.getSource(), "Sack counts cleared. Open /sacks to sync again.");
                            return 1;
                        })))
                .then(literal("hotkeys").executes(ctx -> {
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    mc.schedule(() -> com.auraqol.compat.Mc.setScreen(mc, new HotkeysScreen(null)));
                    return 1;
                }))
                .then(literal("slots")
                        .executes(ctx -> {
                            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                            mc.schedule(() -> com.auraqol.compat.Mc.setScreen(mc, new SlotsScreen(null)));
                            return 1;
                        })
                        .then(literal("save").then(argument("slot", IntegerArgumentType.integer(1, LayoutSlots.COUNT))
                                .executes(ctx -> {
                                    say(ctx.getSource(), LayoutSlots.save(IntegerArgumentType.getInteger(ctx, "slot")));
                                    return 1;
                                })))
                        .then(literal("load").then(argument("slot", IntegerArgumentType.integer(1, LayoutSlots.COUNT))
                                .executes(ctx -> {
                                    say(ctx.getSource(), LayoutSlots.load(IntegerArgumentType.getInteger(ctx, "slot")));
                                    return 1;
                                }))))
                .then(literal("prices")
                        .executes(ctx -> {
                            pricesStatus(ctx.getSource());
                            return 1;
                        })
                        .then(literal("refresh").executes(ctx -> {
                            PriceService.refreshSoon();
                            say(ctx.getSource(), "Fetching prices now. /aq prices in a few seconds shows the result.");
                            return 1;
                        })));
    }

    static boolean isOn(String n) {
        HudConfig c = HudConfig.get();
        return switch (n) {
            case "zoom" -> c.zoomEnabled;
            case "nametag" -> c.selfNametagEnabled;
            case "hotkeys" -> c.hotkeysEnabled;
            case "prices" -> c.priceTooltip;
            case "npc" -> c.priceTooltipNpc;
            case "recipebuy" -> c.recipeBuy;
            case "recipehint" -> c.recipeBuyHint;
            default -> HudLayout.enabled(TOGGLES.get(n));
        };
    }

    static boolean toggle(String n) {
        HudConfig c = HudConfig.get();
        switch (n) {
            case "zoom" -> {
                c.zoomEnabled = !c.zoomEnabled;
                c.save();
                return c.zoomEnabled;
            }
            case "nametag" -> {
                c.selfNametagEnabled = !c.selfNametagEnabled;
                c.save();
                return c.selfNametagEnabled;
            }
            case "hotkeys" -> {
                c.hotkeysEnabled = !c.hotkeysEnabled;
                c.save();
                return c.hotkeysEnabled;
            }
            case "prices" -> {
                c.priceTooltip = !c.priceTooltip;
                c.save();
                return c.priceTooltip;
            }
            case "npc" -> {
                c.priceTooltipNpc = !c.priceTooltipNpc;
                c.save();
                return c.priceTooltipNpc;
            }
            case "recipebuy" -> {
                c.recipeBuy = !c.recipeBuy;
                c.save();
                return c.recipeBuy;
            }
            case "recipehint" -> {
                c.recipeBuyHint = !c.recipeBuyHint;
                c.save();
                return c.recipeBuyHint;
            }
            default -> {
                return HudLayout.toggle(TOGGLES.get(n));
            }
        }
    }

    static String describe(String n) {
        return switch (n) {
            case "info" -> "Info panel";
            case "cps" -> "CPS counter";
            case "keys" -> "Keystrokes";
            case "zoom" -> "Zoom (hold C)";
            case "nametag" -> "Your own nametag in F5";
            case "hotkeys" -> "Chat hotkeys (/aq hotkeys to edit)";
            case "bps" -> "Blocks per second";
            case "money" -> "Money tracker";
            case "todo" -> "To-do list";
            case "comms" -> "Commissions panel";
            case "slayer" -> "Slayer quest panel";
            case "ability" -> "Pickaxe ability timer";
            case "sacks" -> "Sacks panel (crop/ore you are collecting)";
            case "prices" -> "Price tooltips";
            case "npc" -> "NPC line in price tooltips";
            case "recipebuy" -> "Right-click a recipe ingredient -> /bz (Ctrl = normal click)";
            case "recipehint" -> "Chat note after a recipe -> bazaar click";
            case HudLayout.SP_MINING -> "Shadow Prison: blocks and value mined per hour";
            case HudLayout.SP_EARNINGS -> "Shadow Prison: money earned per hour, by source";
            case HudLayout.SP_RANKUP -> "Shadow Prison: time to the next rank-up";
            case HudLayout.SP_BOOSTS -> "Shadow Prison: active boosts and their timers";
            case HudLayout.SP_CONTRACT -> "Shadow Prison: Contracts tracker";
            case HudLayout.SP_SKILLS -> "Shadow Prison: skill XP/hour and next level";
            case HudLayout.SP_PROFILE -> "Shadow Prison: /sp profile card";
            default -> n;
        };
    }

    private static void pricesStatus(FabricClientCommandSource src) {
        PriceData d = PriceService.data();
        long now = System.currentTimeMillis();
        head(src, "Prices");
        say(src, "  Bazaar: " + d.bazaarSize() + " products, "
                + (d.bazaarAt() == 0 ? "never fetched" : Fmt.age(now - d.bazaarAt()) + " old")
                + "  (api.hypixel.net)");
        say(src, "  Lowest BIN: " + d.lbinSize() + " items, "
                + (d.lbinAt() == 0 ? "never fetched" : Fmt.age(now - d.lbinAt()) + " old")
                + (d.lbinSource().isEmpty() ? "" : "  (" + d.lbinSource() + ")"));
        say(src, "  NPC prices: " + d.npcSize() + " items, "
                + (d.itemsAt() == 0 ? "never fetched" : Fmt.age(now - d.itemsAt()) + " old")
                + "  (api.hypixel.net)");
        if (!PriceService.lastError().isEmpty()) {
            say(src, "  Last problem: " + PriceService.lastError());
        }
        if (HudConfig.get().pricesOnlyOnHypixel && !Skyblock.onHypixel()) {
            say(src, "  Not on Hypixel, so nothing is downloaded right now (pricesOnlyOnHypixel).");
        }
    }

    // ------------------------------------------------------------ /todo

    private static LiteralArgumentBuilder<FabricClientCommandSource> todo() {
        SuggestionProvider<FabricClientCommandSource> lists = (ctx, b) -> {
            for (String n : TodoHud.checklistNames()) {
                if (n.toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase())) {
                    b.suggest(n);
                }
            }
            return b.buildFuture();
        };
        return literal("todo")
                .executes(ctx -> listTodo(ctx.getSource()))
                .then(literal("list").executes(ctx -> listTodo(ctx.getSource())))
                .then(literal("add").then(argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                    TodoList l = TodoHud.list();
                    if (l.size() >= TodoList.MAX_ITEMS) {
                        err(ctx.getSource(), "The list is full (" + TodoList.MAX_ITEMS + "). /todo clear done first.");
                        return 0;
                    }
                    if (!l.add(StringArgumentType.getString(ctx, "text"))) {
                        err(ctx.getSource(), "Nothing to add.");
                        return 0;
                    }
                    TodoHud.save();
                    say(ctx.getSource(), "Added #" + l.size() + ".");
                    return 1;
                })))
                .then(literal("done").then(argument("n", IntegerArgumentType.integer(1)).executes(ctx -> {
                    // Sets done rather than toggling: typing the same command
                    // twice must not quietly undo it. /todo undo reverses.
                    int n = IntegerArgumentType.getInteger(ctx, "n");
                    if (!TodoHud.list().setDone(n, true)) {
                        return outOfRange(ctx.getSource(), n);
                    }
                    TodoHud.save();
                    TodoList l = TodoHud.list();
                    say(ctx.getSource(), "#" + n + " done (" + l.doneCount() + "/" + l.size() + ")."
                            + (l.doneCount() == l.size() ? " All done." : ""));
                    return 1;
                })))
                .then(literal("undo").then(argument("n", IntegerArgumentType.integer(1)).executes(ctx -> {
                    int n = IntegerArgumentType.getInteger(ctx, "n");
                    if (!TodoHud.list().setDone(n, false)) {
                        return outOfRange(ctx.getSource(), n);
                    }
                    TodoHud.save();
                    say(ctx.getSource(), "#" + n + " not done.");
                    return 1;
                })))
                .then(literal("remove").then(argument("n", IntegerArgumentType.integer(1)).executes(ctx -> {
                    int n = IntegerArgumentType.getInteger(ctx, "n");
                    TodoList.Item gone = TodoHud.list().remove(n);
                    if (gone == null) {
                        return outOfRange(ctx.getSource(), n);
                    }
                    TodoHud.save();
                    say(ctx.getSource(), "Removed: " + gone.text());
                    return 1;
                })))
                .then(literal("move").then(argument("from", IntegerArgumentType.integer(1))
                        .then(argument("to", IntegerArgumentType.integer(1)).executes(ctx -> {
                            int from = IntegerArgumentType.getInteger(ctx, "from");
                            int to = IntegerArgumentType.getInteger(ctx, "to");
                            if (!TodoHud.list().move(from, to)) {
                                err(ctx.getSource(), "Both numbers must be between 1 and " + TodoHud.list().size() + ".");
                                return 0;
                            }
                            TodoHud.save();
                            say(ctx.getSource(), "Moved #" + from + " to #" + to + ".");
                            return 1;
                        }))))
                .then(literal("clear")
                        .executes(ctx -> {
                            int n = TodoHud.list().size();
                            TodoHud.list().clear();
                            TodoHud.save();
                            say(ctx.getSource(), "Cleared " + n + " item" + (n == 1 ? "" : "s") + ".");
                            return 1;
                        })
                        .then(literal("done").executes(ctx -> {
                            int n = TodoHud.list().clearDone();
                            TodoHud.save();
                            say(ctx.getSource(), "Removed " + n + " done item" + (n == 1 ? "" : "s") + ".");
                            return 1;
                        })))
                .then(literal("lists").executes(ctx -> {
                    List<String> names = TodoHud.checklistNames();
                    head(ctx.getSource(), "Checklists in config/auraqol-todo/");
                    if (names.isEmpty()) {
                        say(ctx.getSource(), "  (none - drop a .json file there)");
                    }
                    for (String n : names) {
                        say(ctx.getSource(), "  " + n + "   -> /todo load " + n);
                    }
                    return 1;
                }))
                .then(literal("load").then(argument("name", StringArgumentType.string()).suggests(lists)
                        .executes(ctx -> load(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false))
                        .then(literal("replace").executes(ctx ->
                                load(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true)))));
    }

    private static int load(FabricClientCommandSource src, String name, boolean replace) {
        String[] why = new String[1];
        List<TodoList.Item> items = TodoHud.readChecklist(name, why);
        if (items == null) {
            err(src, why[0]);
            return 0;
        }
        TodoList l = TodoHud.list();
        if (replace) {
            l.replaceWith(items);
            TodoHud.save();
            say(src, "Replaced the list with " + l.size() + " item" + (l.size() == 1 ? "" : "s") + " from " + name + ".");
        } else {
            int[] r = l.merge(items);
            TodoHud.save();
            say(src, "Added " + r[0] + " from " + name + (r[1] > 0 ? " (" + r[1] + " already on the list)" : "") + ".");
        }
        return 1;
    }

    private static int listTodo(FabricClientCommandSource src) {
        List<TodoList.Item> items = TodoHud.list().items();
        head(src, "To-do (" + TodoHud.list().doneCount() + "/" + items.size() + " done)");
        if (items.isEmpty()) {
            say(src, "  Empty. /todo add <text>, or /todo load <checklist>");
        }
        for (int i = 0; i < items.size(); i++) {
            TodoList.Item it = items.get(i);
            MutableComponent c = Component.literal("  " + (i + 1) + ". ").withColor(0x8A8A8A);
            MutableComponent t = Component.literal(it.text()).withColor(it.done() ? 0x6A6A6A : 0xFFFFFF);
            if (it.done()) {
                t.setStyle(t.getStyle().withStrikethrough(true));
            }
            c.append(t);
            src.sendFeedback(c);
        }
        say(src, "  add <text> | done <n> | undo <n> | remove <n> | move <a> <b> | clear [done] | lists | load <name> [replace]");
        return 1;
    }

    private static int outOfRange(FabricClientCommandSource src, int n) {
        int size = TodoHud.list().size();
        err(src, size == 0 ? "The list is empty." : "No item #" + n + " - the list has " + size + ".");
        return 0;
    }

    // ------------------------------------------------------------ /money

    private static LiteralArgumentBuilder<FabricClientCommandSource> money() {
        return literal("money")
                .executes(ctx -> {
                    head(ctx.getSource(), "Money tracker");
                    for (String s : MoneyTracker.status()) {
                        say(ctx.getSource(), s);
                    }
                    say(ctx.getSource(), "  /money reset | pause | resume | mode [session|recent] | window <1|5|10|30>"
                            + " | breakdown | log | unpriced");
                    return 1;
                })
                .then(literal("mode")
                        .executes(ctx -> {
                            HudConfig c = HudConfig.get();
                            c.moneyRecent = !c.moneyRecent;
                            c.save();
                            say(ctx.getSource(), "Money rate: " + MoneyTracker.modeLabel() + ".");
                            return 1;
                        })
                        .then(literal("session").executes(ctx -> {
                            HudConfig c = HudConfig.get();
                            c.moneyRecent = false;
                            c.save();
                            say(ctx.getSource(), "Money rate: session average (all active time this session).");
                            return 1;
                        }))
                        .then(literal("recent").executes(ctx -> {
                            HudConfig c = HudConfig.get();
                            c.moneyRecent = true;
                            c.save();
                            say(ctx.getSource(), "Money rate: " + MoneyTracker.modeLabel() + " of active play.");
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
                            c.moneyWindowMinutes = HudConfig.snapWindow(IntegerArgumentType.getInteger(ctx, "minutes"));
                            c.moneyRecent = true;
                            c.save();
                            say(ctx.getSource(), "Money rate: " + MoneyTracker.modeLabel() + " of active play (1, 5, 10 or 30).");
                            return 1;
                        })))
                .then(literal("breakdown").executes(ctx -> {
                    HudConfig c = HudConfig.get();
                    c.moneyBreakdown = !c.moneyBreakdown;
                    c.save();
                    say(ctx.getSource(), "Per-source rows " + (c.moneyBreakdown ? "ON" : "OFF") + ".");
                    return 1;
                }))
                .then(literal("log").executes(ctx -> {
                    boolean on = MoneyTracker.toggleLog();
                    say(ctx.getSource(), on
                            ? "Money log ON - every counted/ignored event goes to " + MoneyTracker.logPath()
                                + " (2 MB cap, then .old.txt). /money log again to stop."
                            : "Money log OFF. File: " + MoneyTracker.logPath());
                    return 1;
                }))
                .then(literal("unpriced").executes(ctx -> {
                    Map<String, Integer> up = MoneyTracker.unpriced();
                    head(ctx.getSource(), "Seen this session with no price (not counted): " + up.size());
                    up.forEach((k, v) -> say(ctx.getSource(), "  " + k + "  x" + v));
                    return 1;
                }))
                .then(literal("reset").executes(ctx -> {
                    MoneyTracker.reset();
                    say(ctx.getSource(), "Money session reset.");
                    return 1;
                }))
                .then(literal("pause").executes(ctx -> {
                    if (!MoneyTracker.paused()) {
                        MoneyTracker.togglePause();
                    }
                    say(ctx.getSource(), "Money tracker paused. /money resume to continue.");
                    return 1;
                }))
                .then(literal("resume").executes(ctx -> {
                    if (MoneyTracker.paused()) {
                        MoneyTracker.togglePause();
                    }
                    say(ctx.getSource(), "Money tracker running.");
                    return 1;
                }));
    }

    // ------------------------------------------------------------ /bps

    private static LiteralArgumentBuilder<FabricClientCommandSource> bps() {
        return literal("bps")
                .executes(ctx -> {
                    say(ctx.getSource(), "Blocks/s: " + BlocksPerSecond.current() + " now, peak "
                            + BlocksPerSecond.peak() + ", " + BlocksPerSecond.total() + " broken this session ("
                            + BlocksPerSecond.spreadTotal() + " by Mining Spread).");
                    say(ctx.getSource(), "  /bps reset clears the peak.");
                    return 1;
                })
                .then(literal("reset").executes(ctx -> {
                    BlocksPerSecond.reset();
                    say(ctx.getSource(), "Blocks/s peak reset.");
                    return 1;
                }));
    }

    // ------------------------------------------------------------ chat

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
