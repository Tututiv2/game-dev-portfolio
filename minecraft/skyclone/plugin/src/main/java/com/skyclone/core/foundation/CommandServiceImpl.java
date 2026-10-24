package com.skyclone.core.foundation;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.skyclone.api.Sky;
import com.skyclone.api.command.CommandService;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.config.Dials;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.MenuService;
import com.skyclone.api.ui.Text;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Plain-string commands on top of Paper's Brigadier lifecycle API.
 *
 * <p>Every {@link SkyCommand} becomes {@code literal(name)} (run with no args) plus a greedy
 * {@code args} string (split on spaces) whose suggestions come from the command's completer.
 * Commands registered after Paper's COMMANDS lifecycle event has fired (anything registered from a
 * scheduled task, say) fall back to the Bukkit CommandMap so they still work, with a warning.
 *
 * <p>{@code /sb <sub>} is ONE command whose first word is looked up in the admin registry at run
 * time, so admin subs may be registered at any point.
 */
public final class CommandServiceImpl implements CommandService {

    public static final String ADMIN_PERM = "skyclone.admin";
    private static final Pattern NAME = Pattern.compile("[a-z0-9_\\-]{1,32}");

    /** One {@code /sb <sub>} entry. */
    public record AdminSub(String sub, String usage, String description, SkyCommand.Executor executor, SkyCommand.Completer completer) {}

    private final JavaPlugin plugin;
    private final Logger log;
    private final Map<String, SkyCommand> commands = new LinkedHashMap<>();
    private final Map<String, String> labelOwner = new ConcurrentHashMap<>();   // label/alias -> command name
    private final Map<String, AdminSub> admin = new TreeMap<>();
    private final Map<String, Supplier<String>> selfTests = new LinkedHashMap<>();
    private volatile boolean lifecycleFired;
    private final Map<UUID, long[]> rate = new ConcurrentHashMap<>();
    private final Dials.Dial<Integer> maxPerSecond;
    private final Dials.Dial<Integer> selfTestBudgetMs;
    private SelfTestRun selfTestRun;                       // the current or last run (main thread only)
    private org.bukkit.scheduler.BukkitTask selfTestTask;

    public CommandServiceImpl(JavaPlugin plugin, Dials dials) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.maxPerSecond = dials.integer("commands.max-per-second", 8,
                "Commands a non-admin player may run per second before 'too fast' (anti-spam)");
        this.selfTestBudgetMs = dials.integer("selftest.tick-budget-ms", 25,
                "Main-thread milliseconds /sb selftest may use per tick; the rest of the run continues next tick "
                        + "(a full run in one tick held the server for 10+ s and tripped Paper's watchdog)");
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands reg = event.registrar();
            List<SkyCommand> snapshot;
            synchronized (this) { snapshot = new ArrayList<>(commands.values()); }
            int n = 0;
            for (SkyCommand c : snapshot) {
                try {
                    reg.register(build(c.name, c), c.description, c.aliases);
                    n++;
                } catch (Throwable t) {
                    log.log(Level.SEVERE, "[commands] could not register /" + c.name, t);
                }
            }
            lifecycleFired = true;
            log.info("[commands] registered " + n + " commands with Paper (" + admin.size() + " /sb subs, "
                    + selfTests.size() + " self-tests)");
        });

        // The admin root itself.
        register(SkyCommand.of("sb", "Admin tools: /sb help lists every sub-command")
                .alias("skyclone").admin().usage("<sub> [args]")
                .run(this::runAdminRoot)
                .tab(this::completeAdminRoot));
    }

    // ---- registration ------------------------------------------------------------------------

    @Override
    public synchronized void register(SkyCommand command) {
        if (command == null) return;
        String name = command.name == null ? "" : command.name.toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            log.warning("[commands] refusing command with invalid name '" + command.name + "'");
            return;
        }
        if (command.description == null || command.description.isBlank()) {
            log.warning("[commands] /" + name + " has no description (descriptions are mandatory)");
        }
        if (commands.containsKey(name)) {
            log.warning("[commands] /" + name + " registered twice; keeping the first. Second registration ignored ("
                    + caller() + ")");
            return;
        }
        if (labelOwner.containsKey(name)) {
            log.warning("[commands] /" + name + " is already an alias of /" + labelOwner.get(name) + "; ignored");
            return;
        }
        commands.put(name, command);
        labelOwner.put(name, name);
        List<String> keptAliases = new ArrayList<>();
        for (String a : command.aliases) {
            String al = a.toLowerCase(Locale.ROOT);
            if (!NAME.matcher(al).matches() || labelOwner.containsKey(al)) {
                log.warning("[commands] alias /" + a + " of /" + name + " dropped (invalid or taken by /" + labelOwner.get(al) + ")");
                continue;
            }
            labelOwner.put(al, name);
            keptAliases.add(al);
        }
        command.aliases.clear();
        command.aliases.addAll(keptAliases);
        if (lifecycleFired) registerLate(name, command);
    }

    @Override
    public synchronized void admin(String sub, String usage, String description, SkyCommand.Executor executor, SkyCommand.Completer completer) {
        if (sub == null || executor == null) return;
        String s = sub.toLowerCase(Locale.ROOT);
        if (!NAME.matcher(s).matches() || s.equals("help")) {
            log.warning("[commands] refusing /sb sub with invalid or reserved name '" + sub + "'");
            return;
        }
        if (admin.containsKey(s)) {
            log.warning("[commands] /sb " + s + " registered twice; keeping the first (" + caller() + ")");
            return;
        }
        admin.put(s, new AdminSub(s, usage == null ? "" : usage, description == null ? "" : description, executor,
                completer == null ? (x, y) -> List.of() : completer));
    }

    @Override
    public synchronized void selfTest(String id, Supplier<String> test) {
        if (id == null || test == null) return;
        if (selfTests.containsKey(id)) {
            log.warning("[selftest] " + id + " registered twice; keeping the first");
            return;
        }
        selfTests.put(id, test);
    }

    private static String caller() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        for (StackTraceElement e : st) {
            if (!e.getClassName().startsWith("com.skyclone.core.foundation.")) return e.getClassName() + ":" + e.getLineNumber();
        }
        return "?";
    }

    public synchronized List<SkyCommand> commands() { return new ArrayList<>(commands.values()); }

    public synchronized List<AdminSub> adminSubs() { return new ArrayList<>(admin.values()); }

    public synchronized Map<String, Supplier<String>> selfTests() { return new LinkedHashMap<>(selfTests); }

    public boolean lifecycleFired() { return lifecycleFired; }

    /**
     * Safety net, scheduled once at boot: if Paper's COMMANDS lifecycle event has not reached us
     * by the time the server ticks, nothing would be registered at all. Fall back to the Bukkit
     * command map for every command so the game is still playable, and say so loudly.
     */
    public void startupCheck() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (lifecycleFired) return;
            log.severe("[commands] Paper's COMMANDS lifecycle event never fired - registering every command through the "
                    + "Bukkit command map instead");
            List<SkyCommand> all;
            synchronized (this) {
                lifecycleFired = true;
                all = new ArrayList<>(commands.values());
            }
            for (SkyCommand c : all) {
                try { registerLate(c.name, c); } catch (Throwable t) { log.log(Level.SEVERE, "[commands] fallback for /" + c.name + " failed", t); }
            }
        }, 20L);
    }

    // ---- brigadier ---------------------------------------------------------------------------

    /**
     * Who runs the command: under {@code /execute as <player> run ...} the SENDER is the console (or a
     * command block) and the EXECUTOR is the player, so player commands must see the player. Only
     * operators can use /execute, so this grants nothing a normal player could not already do.
     */
    static org.bukkit.command.CommandSender actor(CommandSourceStack src) {
        org.bukkit.entity.Entity ex = src.getExecutor();
        return ex instanceof Player p ? p : src.getSender();
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> build(String label, SkyCommand c) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(label)
                .requires(src -> c.permission == null || src.getSender().hasPermission(c.permission))
                .executes(ctx -> {
                    execute(c, actor(ctx.getSource()), new String[0]);
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> suggest(c, ctx.getSource().getSender(), builder))
                        .executes(ctx -> {
                            execute(c, actor(ctx.getSource()), split(StringArgumentType.getString(ctx, "args")));
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        }));
        return root.build();
    }

    static String[] split(String input) {
        if (input == null) return new String[0];
        String t = input.trim();
        if (t.isEmpty()) return new String[0];
        return t.split("\\s+");
    }

    /** Split for completion: keeps a trailing empty token when the input ends in a space. */
    static String[] splitForCompletion(String input) {
        if (input == null || input.isEmpty()) return new String[]{""};
        String lead = input.replaceFirst("^\\s+", "");
        String[] parts = lead.split("\\s+", -1);
        return parts.length == 0 ? new String[]{""} : parts;
    }

    private CompletableFuture<Suggestions> suggest(SkyCommand c, CommandSender sender, SuggestionsBuilder builder) {
        try {
            String remaining = builder.getRemaining();
            String[] args = splitForCompletion(remaining);
            String last = args[args.length - 1];
            List<String> options = c.completer.complete(sender, args);
            if (options == null || options.isEmpty()) return builder.buildFuture();
            int lastSpace = Math.max(remaining.lastIndexOf(' '), remaining.lastIndexOf('\t'));
            SuggestionsBuilder sb = builder.createOffset(builder.getStart() + lastSpace + 1);
            String lower = last.toLowerCase(Locale.ROOT);
            int count = 0;
            for (String o : options) {
                if (o == null) continue;
                if (o.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    sb.suggest(o);
                    if (++count >= 200) break;
                }
            }
            return sb.buildFuture();
        } catch (Throwable t) {
            log.log(Level.WARNING, "[commands] tab-complete for /" + c.name + " threw", t);
            return builder.buildFuture();
        }
    }

    // ---- late registration (after the lifecycle event) ---------------------------------------

    private void registerLate(String name, SkyCommand c) {
        log.warning("[commands] /" + name + " registered after command registration closed; using the Bukkit command map fallback ("
                + caller() + ")");
        Command bc = new Command(name, c.description == null ? "" : c.description, "/" + name + " " + c.usage, c.aliases) {
            @Override public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
                if (c.permission != null && !sender.hasPermission(c.permission)) {
                    sender.sendMessage(Text.c("&cYou don't have permission to do that."));
                    return true;
                }
                CommandServiceImpl.this.execute(c, sender, args);
                return true;
            }

            @Override public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
                if (c.permission != null && !sender.hasPermission(c.permission)) return List.of();
                try {
                    List<String> opts = c.completer.complete(sender, args.length == 0 ? new String[]{""} : args);
                    if (opts == null) return List.of();
                    String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
                    List<String> out = new ArrayList<>();
                    for (String o : opts) if (o != null && o.toLowerCase(Locale.ROOT).startsWith(last)) out.add(o);
                    return out;
                } catch (Throwable t) {
                    return List.of();
                }
            }
        };
        if (c.permission != null) bc.setPermission(c.permission);
        Runnable doIt = () -> {
            Bukkit.getCommandMap().register(plugin.getName().toLowerCase(Locale.ROOT), bc);
            for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
        };
        if (Bukkit.isPrimaryThread()) doIt.run(); else Bukkit.getScheduler().runTask(plugin, doIt);
    }

    // ---- execution -----------------------------------------------------------------------------

    @Override
    public boolean exists(String name) {
        if (name == null) return false;
        String k = name.toLowerCase(java.util.Locale.ROOT);
        return labelOwner.containsKey(k) || commands.containsKey(k);
    }

    /** Runs a command with every guard: permission, player-only, rate limit, exception catch. */
    @Override
    public boolean run(Player p, String name, String... args) {
        if (p == null || name == null) return false;
        String owner = labelOwner.get(name.toLowerCase(java.util.Locale.ROOT));
        SkyCommand c = owner == null ? null : commands.get(owner);
        if (c == null) c = commands.get(name.toLowerCase(java.util.Locale.ROOT));
        if (c == null) return false;
        execute(c, p, args == null ? new String[0] : args);
        return true;
    }

    public void execute(SkyCommand c, CommandSender sender, String[] args) {
        if (c.permission != null && !sender.hasPermission(c.permission)) {
            sender.sendMessage(Text.c("&cYou don't have permission to do that."));
            return;
        }
        if (c.playerOnly && !(sender instanceof Player)) {
            sender.sendMessage(Text.c("&cOnly players can use /" + c.name + "."));
            return;
        }
        if (sender instanceof Player p && !p.hasPermission(ADMIN_PERM) && !allow(p.getUniqueId())) {
            sender.sendMessage(Text.c("&cYou're doing that too fast!"));
            return;
        }
        try {
            c.executor.run(sender, args);
        } catch (Throwable t) {
            log.log(Level.SEVERE, "[commands] /" + c.name + " " + String.join(" ", args) + " failed for " + sender.getName(), t);
            sender.sendMessage(Text.c("&cSomething went wrong (logged)"));
        }
    }

    private boolean allow(UUID id) {
        int max = Math.max(1, maxPerSecond.get());
        long now = System.currentTimeMillis();
        long[] w = rate.computeIfAbsent(id, k -> new long[]{now, 0});
        synchronized (w) {
            if (now - w[0] >= 1000) { w[0] = now; w[1] = 0; }
            w[1]++;
            return w[1] <= max;
        }
    }

    public void forget(UUID id) { rate.remove(id); }

    // ---- /sb root ------------------------------------------------------------------------------

    private void runAdminRoot(CommandSender s, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equals("?")) {
            sendAdminHelp(s, args.length > 1 ? args[1] : null);
            return;
        }
        AdminSub sub;
        synchronized (this) { sub = admin.get(args[0].toLowerCase(Locale.ROOT)); }
        if (sub == null) {
            s.sendMessage(Text.c("&cUnknown sub-command '/sb " + args[0] + "'. &7Try &e/sb help&7."));
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            sub.executor().run(s, rest);
        } catch (Throwable t) {
            log.log(Level.SEVERE, "[commands] /sb " + String.join(" ", args) + " failed for " + s.getName(), t);
            s.sendMessage(Text.c("&cSomething went wrong (logged): &7" + t));
        }
    }

    private List<String> completeAdminRoot(CommandSender s, String[] args) {
        if (args.length <= 1) {
            List<String> out = new ArrayList<>();
            out.add("help");
            synchronized (this) { out.addAll(admin.keySet()); }
            return out;
        }
        if (args[0].equalsIgnoreCase("help")) {
            synchronized (this) { return new ArrayList<>(admin.keySet()); }
        }
        AdminSub sub;
        synchronized (this) { sub = admin.get(args[0].toLowerCase(Locale.ROOT)); }
        if (sub == null) return List.of();
        return sub.completer().complete(s, Arrays.copyOfRange(args, 1, args.length));
    }

    private void sendAdminHelp(CommandSender s, String filter) {
        List<AdminSub> subs = adminSubs();
        s.sendMessage(Text.c("&6&lAdmin commands &7(" + subs.size() + ")" + (filter != null ? " &8matching '" + filter + "'" : "")));
        for (AdminSub a : subs) {
            if (filter != null && !a.sub().contains(filter.toLowerCase(Locale.ROOT))) continue;
            s.sendMessage(Text.c("&e/sb " + a.sub() + (a.usage().isEmpty() ? "" : " &7" + a.usage()) + " &8- &f" + a.description()));
        }
    }

    // ---- self-tests ----------------------------------------------------------------------------

    /**
     * {@code /sb selftest [filter]} and {@code /sb selftest report}.
     *
     * <p>Tests run on the main thread (they touch Bukkit), but no longer all in one tick: a run takes
     * at most {@code selftest.tick-budget-ms} per tick and continues on the next one, so 357 tests no
     * longer freeze the server for 10+ seconds and trip Paper's watchdog. A run that fits in the first
     * slice (any filtered run, usually) answers exactly as before, in one reply. A longer one answers
     * {@value SelfTestRun#RUNNING} and keeps going; {@code /sb selftest report} prints every line so far
     * and the summary once done (RCON gets its reply only while the command runs, so this is how a
     * script reads the result: {@code scripts/rcon.ps1} polls it automatically). The last report is
     * also written to {@code plugins/SkyClone/selftest-last.txt}.
     *
     * @return {passed, failed} so far (the final numbers only when the run finished in this call)
     */
    public int[] runSelfTests(CommandSender s, String arg) {
        if (arg != null && (arg.equalsIgnoreCase("report") || arg.equalsIgnoreCase("status"))) {
            SelfTestRun run = selfTestRun;
            if (run == null) {
                s.sendMessage("SELFTEST: no run yet - '/sb selftest [filter]' starts one");
                return new int[]{0, 0};
            }
            for (String line : run.report()) s.sendMessage(line);
            return new int[]{run.passed(), run.failed()};
        }
        if (selfTestRun != null && !selfTestRun.done()) {
            s.sendMessage(selfTestRun.progressLine());
            s.sendMessage("SELFTEST: a run is already in progress; wait for it ('/sb selftest report')");
            return new int[]{selfTestRun.passed(), selfTestRun.failed()};
        }
        SelfTestRun run = new SelfTestRun(selfTests(), arg, System::nanoTime,
                (id, t) -> log.log(Level.WARNING, "[selftest] " + id + " threw", t));
        selfTestRun = run;
        for (SelfTestRun.Result r : run.step(budgetNanos())) s.sendMessage(r.line());
        if (run.done()) {
            finishSelfTests(run, s, true);
            return new int[]{run.passed(), run.failed()};
        }
        s.sendMessage(run.progressLine());
        // Later lines go only to a sender that can still receive them: an RCON reply is closed the
        // moment this command returns, so RCON reads the result with '/sb selftest report'.
        final CommandSender live = s instanceof org.bukkit.command.RemoteConsoleCommandSender ? null : s;
        selfTestTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (selfTestRun != run) { cancelSelfTestTask(); return; }
            List<SelfTestRun.Result> slice = run.step(budgetNanos());
            boolean reachable = live != null && (!(live instanceof Player p) || p.isOnline());
            if (reachable) for (SelfTestRun.Result r : slice) live.sendMessage(r.line());
            if (run.done()) {
                cancelSelfTestTask();
                finishSelfTests(run, live, reachable);
            }
        }, 1L, 1L);
        return new int[]{run.passed(), run.failed()};
    }

    private long budgetNanos() {
        int ms = Math.max(5, Math.min(1000, selfTestBudgetMs.get()));
        return ms * 1_000_000L;
    }

    private void cancelSelfTestTask() {
        if (selfTestTask != null) { selfTestTask.cancel(); selfTestTask = null; }
    }

    private void finishSelfTests(SelfTestRun run, CommandSender s, boolean tell) {
        String summary = run.summary();
        if (tell && s != null) {
            s.sendMessage(summary);
            if (run.slices() > 1) s.sendMessage(run.timingLine());
        }
        String f = run.filter();
        log.info("[selftest] " + summary + (f != null ? " (filter '" + f + "')" : ""));
        log.info("[selftest] " + run.timingLine());
        for (SelfTestRun.Result r : run.results()) if (!r.passed()) log.warning("[selftest] " + r.line());
        for (SelfTestRun.Result r : run.slowTests()) {
            log.warning("[selftest] " + r.id() + " took " + r.millis() + " ms on its own; a single test cannot be "
                    + "split across ticks, so its owner should make it cheaper or split it");
        }
        try {
            File out = new File(plugin.getDataFolder(), "selftest-last.txt");
            Files.createDirectories(out.getParentFile().toPath());
            List<String> lines = new ArrayList<>();
            lines.add("# /sb selftest" + (f != null ? " " + f : "") + " - "
                    + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            lines.addAll(run.report());
            Files.write(out.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.log(Level.WARNING, "[selftest] could not write selftest-last.txt", e);
        }
    }

    // ---- /commands -----------------------------------------------------------------------------

    /** One line of the command catalogue. */
    public record Entry(String label, String description, String category, String usage, List<String> aliases,
                        String permission, SkyCommand command) {}

    public List<Entry> catalogue(CommandSender viewer) {
        List<Entry> out = new ArrayList<>();
        for (SkyCommand c : commands()) {
            if (viewer != null && c.permission != null && !viewer.hasPermission(c.permission)) continue;
            out.add(new Entry("/" + c.name, c.description, c.category == null ? "General" : c.category, c.usage,
                    List.copyOf(c.aliases), c.permission, c));
        }
        if (viewer == null || viewer.hasPermission(ADMIN_PERM)) {
            for (AdminSub a : adminSubs()) {
                out.add(new Entry("/sb " + a.sub(), a.description(), "Admin", a.usage(), List.of(), ADMIN_PERM, null));
            }
        }
        out.sort(Comparator.comparing((Entry e) -> categoryOrder(e.category())).thenComparing(Entry::category).thenComparing(Entry::label));
        return out;
    }

    private static int categoryOrder(String cat) {
        return switch (cat) {
            case "General" -> 0;
            case "Admin" -> 100;
            default -> 10;
        };
    }

    public void showCommands(CommandSender s) {
        List<Entry> list = catalogue(s);
        if (s instanceof Player p && Sky.has(MenuService.class)) {
            try {
                Sky.menus().paged("Commands (" + list.size() + ")", list, this::icon, click -> {
                    Entry e = click.entry();
                    Player who = click.click().player();
                    who.closeInventory();
                    if (e.command() != null && !"sb".equals(e.command().name)) {
                        execute(e.command(), who, new String[0]);
                    } else {
                        who.sendMessage(Text.c("&eUsage: &f" + e.label() + (e.usage().isEmpty() ? "" : " " + e.usage())
                                + " &8- &7" + e.description()));
                    }
                }, m -> m.set(4, Icons.of(Material.BOOK, "&aCommands",
                        "&7Every command on this server,", "&7grouped by category.", "",
                        "&7Total: &e" + list.size(), "", "&7Type &e/commands chat &7for a text list.")), 0).open(p);
                return;
            } catch (Throwable t) {
                log.log(Level.WARNING, "[commands] /commands menu failed, falling back to chat", t);
            }
        }
        sendCommandsChat(s, list);
    }

    public void sendCommandsChat(CommandSender s, List<Entry> list) {
        String cat = null;
        s.sendMessage(Text.c("&6&lCommands &7(" + list.size() + ")"));
        for (Entry e : list) {
            if (!e.category().equals(cat)) {
                cat = e.category();
                s.sendMessage(Text.c("&b&l" + cat));
            }
            String al = e.aliases().isEmpty() ? "" : " &8(" + String.join(", ", e.aliases().stream().map(a -> "/" + a).toList()) + ")";
            s.sendMessage(Text.c(" &e" + e.label() + (e.usage().isEmpty() ? "" : " &7" + e.usage()) + al + " &8- &f" + e.description()));
        }
    }

    private ItemStack icon(Entry e) {
        Material m = switch (e.category().toLowerCase(Locale.ROOT)) {
            case "admin" -> Material.COMMAND_BLOCK;
            case "economy" -> Material.GOLD_INGOT;
            case "combat" -> Material.IRON_SWORD;
            case "skills" -> Material.DIAMOND_SWORD;
            case "travel", "warp", "warps" -> Material.ENDER_PEARL;
            case "profile", "profiles", "social" -> Material.NAME_TAG;
            case "mining" -> Material.IRON_PICKAXE;
            case "farming", "garden" -> Material.WHEAT;
            case "fishing" -> Material.FISHING_ROD;
            case "menus", "general" -> Material.PAPER;
            default -> Material.MAP;
        };
        List<String> lore = new ArrayList<>();
        lore.add("&8" + e.category());
        lore.add("");
        lore.addAll(Text.wrap("&7" + e.description(), 34));
        if (!e.usage().isEmpty()) { lore.add(""); lore.add("&7Usage: &f" + e.label() + " " + e.usage()); }
        if (!e.aliases().isEmpty()) lore.add("&7Aliases: &f" + String.join(", ", e.aliases().stream().map(a -> "/" + a).toList()));
        lore.add("");
        lore.add(e.command() != null && !"sb".equals(e.command().name) ? "&eClick to run!" : "&eClick to see usage!");
        return Icons.of(m, "&a" + e.label(), lore);
    }

    // ---- /sb commands dump -------------------------------------------------------------------------

    /** docs/ next to the server folder when it exists, else plugins/SkyClone/. */
    public File dumpTarget() {
        // normalize(): the world container is "." so getAbsoluteFile() is ".../server/." and its
        // parent would be the server folder itself, not the project root that holds docs/.
        File server = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize().toFile();
        File docs = new File(server.getParentFile(), "docs");
        if (docs.isDirectory()) return new File(docs, "COMMANDS.md");
        return new File(plugin.getDataFolder(), "COMMANDS.md");
    }

    public String markdown(String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Commands\n\n");
        sb.append("> Generated by `/sb commands dump` on ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(". Do not edit by hand; regenerate it.\n\n");
        List<Entry> all = catalogue(null);
        String cat = null;
        for (Entry e : all) {
            if (!e.category().equals(cat)) {
                cat = e.category();
                sb.append("\n## ").append(cat).append("\n\n| Command | Usage | Aliases | Description | Permission |\n|---|---|---|---|---|\n");
            }
            sb.append("| `").append(e.label()).append("` | ").append(md(e.usage())).append(" | ")
                    .append(e.aliases().isEmpty() ? "" : md(String.join(", ", e.aliases().stream().map(a -> "/" + a).toList())))
                    .append(" | ").append(md(Text.strip(e.description()))).append(" | ")
                    .append(e.permission() == null ? "everyone" : e.permission()).append(" |\n");
        }
        sb.append("\n## Self-tests\n\nRun with `/sb selftest [filter]` (works over RCON). A long run is spread "
                + "across ticks; `/sb selftest report` prints its result, and `scripts/rcon.ps1` polls that for you.\n\n");
        List<String> ids = new ArrayList<>(selfTests().keySet());
        Collections.sort(ids);
        for (String id : ids) sb.append("- `").append(id).append("`\n");
        if (extra != null) sb.append('\n').append(extra);
        return sb.toString();
    }

    private static String md(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;");
    }

    public void dump(CommandSender s, String extra) {
        File target = dumpTarget();
        String text = markdown(extra);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                File parent = target.getParentFile();
                if (parent != null) parent.mkdirs();
                Files.writeString(target.toPath(), text, StandardCharsets.UTF_8);
                log.info("[commands] wrote " + target.getAbsolutePath());
            } catch (Exception e) {
                log.log(Level.SEVERE, "[commands] could not write " + target, e);
            }
        });
        s.sendMessage("Writing " + target.getAbsolutePath() + " (" + text.length() + " chars)");
    }
}
