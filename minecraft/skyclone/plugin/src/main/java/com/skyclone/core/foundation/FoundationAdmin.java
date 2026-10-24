package com.skyclone.core.foundation;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemService;
import com.skyclone.api.item.RecipeService;
import com.skyclone.api.mob.MobService;
import com.skyclone.api.npc.NpcService;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.MenuService;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.world.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Every /sb sub the foundation owns, the /commands and /calendar player commands, builtin requirement resolvers and self-tests. */
final class FoundationAdmin {

    private final FoundationCore.Parts c;

    FoundationAdmin(FoundationCore.Parts parts) { this.c = parts; }

    void register() {
        CommandServiceImpl cmds = c.commands();

        // ---- player commands ------------------------------------------------------------------
        cmds.register(SkyCommand.of("commands", "List every command on the server, grouped by category")
                .alias("cmds").category("General").usage("[chat]")
                .run((s, a) -> {
                    if (a.length > 0 && a[0].equalsIgnoreCase("chat")) cmds.sendCommandsChat(s, cmds.catalogue(s));
                    else cmds.showCommands(s);
                })
                .tab((s, a) -> List.of("chat")));
        // /calendar belongs to the menus module (its menu adds the mayor); ours is registered in
        // afterModules() only if no module took the name. See registerCalendarFallback().


        // ---- /sb subs -----------------------------------------------------------------------------
        cmds.admin("info", "", "Version, modules, content counts, TPS, database health", (s, a) -> info(s));
        cmds.admin("selftest", "[filter] | report", "Run self-tests (one PASS/FAIL line each; spread across ticks, "
                        + "'report' prints the last run; RCON-friendly)",
                (s, a) -> cmds.runSelfTests(s, a.length > 0 ? a[0] : null),
                (s, a) -> {
                    if (a.length > 1) return List.of();
                    List<String> out = new ArrayList<>();
                    out.add("report");
                    out.addAll(cmds.selfTests().keySet());
                    return out;
                });
        cmds.admin("commands", "<dump|list>", "Write docs/COMMANDS.md, or list commands in chat", (s, a) -> {
            if (a.length > 0 && a[0].equalsIgnoreCase("dump")) cmds.dump(s, dialsMarkdown());
            else cmds.sendCommandsChat(s, cmds.catalogue(null));
        }, (s, a) -> a.length <= 1 ? List.of("dump", "list") : List.of());
        cmds.admin("dial", "<list [filter] | get <key> | set <key> <value> | reset <key>>",
                "View and change any tunable live (persists to dials.yml)", this::dial, this::dialComplete);
        cmds.admin("coins", "<player> <give|take|set> <amount>", "Change a purse (offline 'give' is paid into their active profile)",
                (s, a) -> money(s, a, "coins"), this::moneyComplete);
        cmds.admin("bits", "<player> <give|take|set> <amount>", "Change a player's bits (online only)",
                (s, a) -> money(s, a, "bits"), this::moneyComplete);
        cmds.admin("bank", "<player> <give|take|set|cap> <amount>", "Change a profile's bank balance or capacity (online only)",
                (s, a) -> money(s, a, "bank"), (s, a) -> a.length <= 1 ? ProfileCommands.onlineNames(s)
                        : a.length == 2 ? List.of("give", "take", "set", "cap") : List.of());
        cmds.admin("ledger", "<player> [n]", "Recent coin/bits ledger rows for a player (newest last)", this::ledger,
                (s, a) -> a.length <= 1 ? ProfileCommands.onlineNames(s) : List.of());
        cmds.admin("profile", "<player>", "Show a player's loaded profile: id, members, balances, data keys", (s, a) -> {
            if (a.length < 1) { s.sendMessage("Usage: /sb profile <player>"); return; }
            Player t = Bukkit.getPlayerExact(a[0]);
            if (t == null) { s.sendMessage("Not online: " + a[0]); return; }
            for (String line : c.profiles().describe(t)) s.sendMessage(line);
        }, (s, a) -> a.length <= 1 ? ProfileCommands.onlineNames(s) : List.of());
        cmds.admin("save", "", "Save every loaded profile now", (s, a) -> {
            c.profiles().saveAll().whenComplete((r, ex) -> Sky.log().info("[profiles] /sb save " + (ex == null ? "done" : "FAILED: " + ex)));
            s.sendMessage("Saving " + c.profiles().sessionCount() + " session(s); last save wrote " + c.profiles().lastSaveDocs
                    + " document(s) in " + c.profiles().lastSaveDurationMs + "ms");
        });
        cmds.admin("calendar", "[force <id> on|off|clear | skip <days>]", "Show the date and events; force an event or move time",
                this::calendarAdmin, this::calendarComplete);
        cmds.admin("requirement", "<player> <key>", "Check a requirement key for a player (debug)", (s, a) -> {
            if (a.length < 2) { s.sendMessage("Usage: /sb requirement <player> <key>"); return; }
            Player t = Bukkit.getPlayerExact(a[0]);
            if (t == null) { s.sendMessage("Not online: " + a[0]); return; }
            String key = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length));
            boolean ok = c.requirements().check(t, key);
            s.sendMessage((ok ? "MET " : "NOT MET ") + key + " -> " + Text.strip(c.requirements().describe(key))
                    + " | known prefixes: " + String.join(", ", c.requirements().prefixes()));
        });

        registerResolvers();
        registerSelfTests();
    }

    /** Called from FoundationCore.afterModules(): our Calendar menu, unless a module registered /calendar. */
    void registerCalendarFallback() {
        CommandServiceImpl cmds = c.commands();
        boolean taken = false;
        for (SkyCommand sc : cmds.commands()) {
            if (sc.name.equals("calendar") || sc.aliases.contains("calendar")) { taken = true; break; }
        }
        if (taken) return;
        cmds.register(SkyCommand.of("calendar", "Calendar and Events: today's date and every upcoming event")
                .alias("cal").category("General")
                .run((s, a) -> {
                    if (s instanceof Player p && Sky.has(MenuService.class) && (a.length == 0 || !a[0].equalsIgnoreCase("chat"))) {
                        calendarMenu(p).open(p);
                    } else {
                        calendarText(s, 10);
                    }
                })
                .tab((s, a) -> List.of("chat")));
        Sky.log().info("[foundation] no module registered /calendar - using the core calendar menu");
    }

    // ---- /sb info ---------------------------------------------------------------------------------

    void info(CommandSender s) {
        FoundationCore.BootReport r = FoundationCore.REPORT;
        s.sendMessage("SkyClone " + Sky.plugin().getPluginMeta().getVersion() + " on " + Bukkit.getVersion());
        s.sendMessage("Core: " + r.coreSummary());
        s.sendMessage("Modules: " + r.enabled.size() + " enabled, " + r.failed.size() + " failed"
                + (r.failed.isEmpty() ? "" : " " + r.failed.keySet())
                + ", " + r.missing.size() + " not present" + (r.missing.isEmpty() ? "" : " " + r.missing));
        s.sendMessage("Content: " + FoundationCore.contentCounts());
        double[] tps = Bukkit.getTPS();
        s.sendMessage(String.format(Locale.US, "TPS: %.1f / %.1f / %.1f, mspt %.1f, players %d", tps[0], tps[1], tps[2],
                Bukkit.getAverageTickTime(), Bukkit.getOnlinePlayers().size()));
        SqliteDatabase db = c.db();
        s.sendMessage("Database: queue " + db.queueSize() + ", completed " + db.completedCount() + ", failed " + db.failedCount()
                + ", file " + (db.file().length() / 1024) + " KB");
        ProfileServiceImpl pr = c.profiles();
        s.sendMessage("Profiles: " + pr.sessionCount() + " sessions, " + pr.loadedProfileCount() + " loaded profiles, last save "
                + pr.lastSaveDocs + " docs in " + pr.lastSaveDurationMs + "ms");
        int over = 0;
        for (DialsImpl.DialImpl<?> d : c.dials().all()) if (d.overridden()) over++;
        s.sendMessage("Dials: " + c.dials().all().size() + " (" + over + " overridden, " + c.dials().orphanOverrides().size()
                + " orphan overrides)");
        CommandServiceImpl cmds = c.commands();
        s.sendMessage("Commands: " + cmds.commands().size() + ", /sb subs " + cmds.adminSubs().size() + ", self-tests "
                + cmds.selfTests().size() + ", requirement prefixes " + c.requirements().prefixes().size());
        SkyDate d = c.calendar().now();
        s.sendMessage("Calendar: " + d.dayLabel() + ", Year " + d.year() + " " + d.timeLabel() + " (" + c.calendar().events().size() + " events)");
    }

    // ---- /sb dial ---------------------------------------------------------------------------------

    private void dial(CommandSender s, String[] a) {
        DialsImpl dials = c.dials();
        String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                String filter = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : null;
                int n = 0;
                for (DialsImpl.DialImpl<?> d : dials.all()) {
                    if (filter != null && !d.key().toLowerCase(Locale.ROOT).contains(filter)) continue;
                    n++;
                    s.sendMessage(Text.c(dialLine(d)));
                }
                for (String orphan : dials.orphanOverrides()) {
                    if (filter != null && !orphan.toLowerCase(Locale.ROOT).contains(filter)) continue;
                    s.sendMessage(Text.c("&8? " + orphan + " &7(override in dials.yml, no code registers it)"));
                }
                s.sendMessage(Text.c("&7" + n + " dial(s). &6* &7= overridden in dials.yml. Value then &8(default)&7."));
            }
            case "get" -> {
                if (a.length < 2) { s.sendMessage("Usage: /sb dial get <key>"); return; }
                DialsImpl.DialImpl<?> d = dials.find(a[1]);
                if (d == null) { s.sendMessage("No dial '" + a[1] + "'. Try /sb dial list " + a[1]); return; }
                s.sendMessage(Text.c(dialLine(d)));
                s.sendMessage(Text.c("&7" + d.description()));
            }
            case "set" -> {
                if (a.length < 3) { s.sendMessage("Usage: /sb dial set <key> <value>"); return; }
                DialsImpl.DialImpl<?> d = dials.find(a[1]);
                if (d == null) { s.sendMessage("No dial '" + a[1] + "'. Try /sb dial list " + a[1]); return; }
                String value = String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length));
                String err = d.setFromText(value);
                if (err != null) { s.sendMessage("Not set: " + err); return; }
                Sky.log().info("[dials] " + s.getName() + " set " + d.key() + " = " + DialsImpl.format(d.get()));
                s.sendMessage(Text.c(dialLine(d)));
            }
            case "reset" -> {
                if (a.length < 2) { s.sendMessage("Usage: /sb dial reset <key>"); return; }
                DialsImpl.DialImpl<?> d = dials.find(a[1]);
                if (d == null) { s.sendMessage("No dial '" + a[1] + "'"); return; }
                d.reset();
                Sky.log().info("[dials] " + s.getName() + " reset " + d.key());
                s.sendMessage(Text.c(dialLine(d)));
            }
            default -> s.sendMessage("Usage: /sb dial <list [filter] | get <key> | set <key> <value> | reset <key>>");
        }
    }

    static String dialLine(DialsImpl.DialImpl<?> d) {
        String v = DialsImpl.format(d.get());
        String def = DialsImpl.format(d.defaultValue());
        String mark = d.overridden() ? (v.equals(def) ? "&6*&e(=default) " : "&6* ") : "  ";
        return mark + "&e" + d.key() + " &7= " + (d.overridden() ? "&a" : "&f") + v + " &8(default " + def + ", "
                + d.kind().name().toLowerCase(Locale.ROOT) + ")";
    }

    private List<String> dialComplete(CommandSender s, String[] a) {
        if (a.length <= 1) return List.of("list", "get", "set", "reset");
        if (a.length == 2 && !a[0].equalsIgnoreCase("list")) {
            List<String> keys = new ArrayList<>();
            for (DialsImpl.DialImpl<?> d : c.dials().all()) keys.add(d.key());
            return keys;
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("set")) {
            DialsImpl.DialImpl<?> d = c.dials().find(a[1]);
            if (d != null) {
                if (d.kind() == DialsImpl.Kind.FLAG) return List.of("true", "false");
                return List.of(DialsImpl.format(d.get()), DialsImpl.format(d.defaultValue()));
            }
        }
        return List.of();
    }

    private String dialsMarkdown() {
        StringBuilder sb = new StringBuilder("## Dials\n\nChange live with `/sb dial set <key> <value>`; `/sb dial reset <key>` returns to the default.\n\n"
                + "| Key | Default | Current | Type | Description |\n|---|---|---|---|---|\n");
        for (DialsImpl.DialImpl<?> d : c.dials().all()) {
            sb.append("| `").append(d.key()).append("` | ").append(DialsImpl.format(d.defaultValue())).append(" | ")
                    .append(DialsImpl.format(d.get())).append(d.overridden() ? " *" : "").append(" | ")
                    .append(d.kind().name().toLowerCase(Locale.ROOT)).append(" | ")
                    .append(d.description().replace("|", "\\|")).append(" |\n");
        }
        return sb.toString();
    }

    // ---- /sb coins | bits | bank ----------------------------------------------------------------------

    private List<String> moneyComplete(CommandSender s, String[] a) {
        if (a.length <= 1) return ProfileCommands.onlineNames(s);
        if (a.length == 2) return List.of("give", "take", "set");
        return List.of();
    }

    private void money(CommandSender s, String[] a, String currency) {
        if (a.length < 3) { s.sendMessage("Usage: /sb " + currency + " <player> <give|take|set" + (currency.equals("bank") ? "|cap" : "") + "> <amount>"); return; }
        String op = a[1].toLowerCase(Locale.ROOT);
        double amount = Coins.parse(a[2]);
        if (!Coins.valid(amount)) { s.sendMessage("Bad amount '" + a[2] + "' (use 1500, 2.5k, 3m...)"); return; }
        String reason = "admin:" + op + " by " + s.getName();
        EconomyServiceImpl eco = c.economy();
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || !c.profiles().isLoaded(t)) {
            if (currency.equals("coins") && op.equals("give")) {
                OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(a[0]);
                UUID u = off != null ? off.getUniqueId() : parseUuid(a[0]);
                if (u == null) { s.sendMessage("Unknown player '" + a[0] + "'"); return; }
                eco.depositOffline(u, amount, reason).whenComplete((r, ex) -> Sky.log().info("[economy] offline give "
                        + Coins.format(amount) + " to " + a[0] + (ex == null ? " done" : " FAILED: " + ex.getMessage())));
                s.sendMessage("Queued offline payment of " + Coins.format(amount) + " coins to " + a[0] + " (see console for the result)");
                return;
            }
            s.sendMessage("Player must be online and loaded for that (" + a[0] + ")");
            return;
        }
        switch (currency) {
            case "coins" -> {
                switch (op) {
                    case "give" -> eco.deposit(t, amount, reason);
                    case "take" -> { if (!eco.withdraw(t, amount, reason)) { s.sendMessage("Purse too small: " + Coins.format(eco.purse(t))); return; } }
                    case "set" -> eco.setPurse(t, amount, reason);
                    default -> { s.sendMessage("Op must be give, take or set"); return; }
                }
                s.sendMessage(t.getName() + " purse: " + Coins.format(eco.purse(t)));
            }
            case "bits" -> {
                long n = (long) Math.floor(amount);
                switch (op) {
                    case "give" -> eco.addBits(t, n, reason);
                    case "take" -> { if (!eco.spendBits(t, n, reason)) { s.sendMessage("Not enough bits: " + eco.bits(t)); return; } }
                    case "set" -> eco.setBits(t, n, reason);
                    default -> { s.sendMessage("Op must be give, take or set"); return; }
                }
                s.sendMessage(t.getName() + " bits: " + eco.bits(t));
            }
            case "bank" -> {
                switch (op) {
                    case "give" -> eco.bankCredit(t, amount, reason);
                    case "take" -> eco.setBank(t, Math.max(0, eco.bank(t) - amount), reason);
                    case "set" -> eco.setBank(t, amount, reason);
                    case "cap" -> eco.setBankCapacity(t, amount);
                    default -> { s.sendMessage("Op must be give, take, set or cap"); return; }
                }
                s.sendMessage(t.getName() + " bank: " + Coins.format(eco.bank(t)) + " / " + Coins.format(eco.bankCapacity(t)));
            }
            default -> { }
        }
    }

    static UUID parseUuid(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private void ledger(CommandSender s, String[] a) {
        if (a.length < 1) { s.sendMessage("Usage: /sb ledger <player> [n]"); return; }
        int n = 15;
        if (a.length > 1) {
            try { n = Math.max(1, Math.min(200, Integer.parseInt(a[1]))); } catch (NumberFormatException ignored) { }
        }
        Player online = Bukkit.getPlayerExact(a[0]);
        UUID u = online != null ? online.getUniqueId() : null;
        if (u == null) {
            OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(a[0]);
            u = off != null ? off.getUniqueId() : parseUuid(a[0]);
        }
        if (u == null) { s.sendMessage("Unknown player '" + a[0] + "'"); return; }
        List<EconomyServiceImpl.LedgerRow> mem = c.economy().recent(u, n);
        if (!mem.isEmpty()) {
            s.sendMessage("Ledger for " + a[0] + " (last " + mem.size() + " since boot, newest last):");
            for (EconomyServiceImpl.LedgerRow r : mem) s.sendMessage(EconomyServiceImpl.formatRow(r));
            return;
        }
        if (!(s instanceof Player)) {
            // Console/RCON only sees output sent while the command runs, so wait (bounded) for the DB
            // here. The DB has its own thread, so this cannot deadlock; it is an admin-only path.
            try {
                List<EconomyServiceImpl.LedgerRow> rows = c.economy().ledgerFromDb(u, n).get(5, java.util.concurrent.TimeUnit.SECONDS);
                s.sendMessage("Ledger for " + a[0] + " (last " + rows.size() + " from the database, newest last):");
                for (EconomyServiceImpl.LedgerRow r : rows) s.sendMessage(EconomyServiceImpl.formatRow(r));
            } catch (Exception ex) {
                s.sendMessage("Ledger read failed: " + ex);
            }
            return;
        }
        UUID fu = u;
        int fn = n;
        s.sendMessage("Reading the ledger from the database...");
        c.economy().ledgerFromDb(fu, fn).whenComplete((rows, ex) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (ex != null) { s.sendMessage("Ledger read failed: " + ex.getMessage()); return; }
            s.sendMessage("Ledger for " + a[0] + " (last " + rows.size() + ", newest last):");
            for (EconomyServiceImpl.LedgerRow r : rows) s.sendMessage(EconomyServiceImpl.formatRow(r));
        }));
    }

    // ---- calendar --------------------------------------------------------------------------------

    private void calendarAdmin(CommandSender s, String[] a) {
        CalendarServiceImpl cal = c.calendar();
        if (a.length >= 1 && a[0].equalsIgnoreCase("force")) {
            if (a.length < 3) { s.sendMessage("Usage: /sb calendar force <id> on|off|clear"); return; }
            if (cal.event(a[1]) == null) { s.sendMessage("No event '" + a[1] + "'"); return; }
            switch (a[2].toLowerCase(Locale.ROOT)) {
                case "on" -> cal.force(a[1], true);
                case "off" -> cal.force(a[1], false);
                case "clear" -> cal.clearForce(a[1]);
                default -> { s.sendMessage("on, off or clear"); return; }
            }
            s.sendMessage(a[1] + " is now " + (cal.active(a[1]) ? "ACTIVE" : "inactive") + (cal.isForced(a[1]) ? " (forced)" : ""));
            return;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("skip")) {
            if (a.length < 2) { s.sendMessage("Usage: /sb calendar skip <days> (decimals and negatives ok)"); return; }
            double days;
            try { days = Double.parseDouble(a[1]); } catch (NumberFormatException e) { s.sendMessage("Bad number"); return; }
            if (!Double.isFinite(days) || Math.abs(days) > 372 * 1000) { s.sendMessage("Bad number"); return; }
            cal.skipDays(days);
            s.sendMessage("Skipped " + days + " day(s).");
        }
        calendarText(s, 10);
    }

    private List<String> calendarComplete(CommandSender s, String[] a) {
        if (a.length <= 1) return List.of("force", "skip");
        if (a.length == 2 && a[0].equalsIgnoreCase("force")) {
            List<String> ids = new ArrayList<>();
            for (CalendarEvent e : c.calendar().events()) ids.add(e.id);
            return ids;
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("force")) return List.of("on", "off", "clear");
        return List.of();
    }

    void calendarText(CommandSender s, int n) {
        CalendarServiceImpl cal = c.calendar();
        SkyDate d = cal.now();
        s.sendMessage(Text.c("&e" + d.dayLabel() + ", Year " + d.year() + " &7" + d.timeLabel() + (d.isNight() ? " &bnight" : " &eday")));
        List<String> active = new ArrayList<>();
        for (CalendarEvent e : cal.events()) if (cal.active(e.id)) active.add(e.name == null ? e.id : e.name);
        s.sendMessage(Text.c("&7Active: " + (active.isEmpty() ? "&8none" : "&a" + String.join("&7, &a", active))));
        s.sendMessage(Text.c("&7Next " + n + ":"));
        for (CalendarEvent e : cal.upcoming(n)) {
            long until = cal.millisUntil(e.id);
            String when = until == 0 ? "&aACTIVE &7(ends in " + Text.duration(cal.millisRemaining(e.id) / 1000) + ")"
                    : until < 0 ? "&8never" : "&7in &e" + Text.duration(until / 1000);
            s.sendMessage(Text.c(" &f" + (e.name == null ? e.id : e.name) + " &8(" + e.id + ") " + when + (cal.isForced(e.id) ? " &6[forced]" : "")));
        }
    }

    Menu calendarMenu(Player viewer) {
        Menu m = new Menu("Calendar and Events", 6);
        m.drawer(menu -> drawCalendar(menu)).refreshEvery(20);
        if (Sky.has(Features.SkyMenu.class)) {
            // set in the drawer too, but the back button must exist even before the first draw
            m.set(m.closeSlot() - 1, Icons.back("SkyBlock Menu"), cl -> Sky.service(Features.SkyMenu.class).open(cl.player()));
        }
        return m;
    }

    private static final int[] INNER = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final String[] SEASONS = {"Spring", "Summer", "Autumn", "Winter"};

    private void drawCalendar(Menu m) {
        CalendarServiceImpl cal = c.calendar();
        m.chrome();
        SkyDate d = cal.now();
        long dayMin = cal.millisPerDay() / 60_000L;
        m.set(4, Icons.of(Material.CLOCK, "&a" + d.dayLabel() + "&7, Year &e" + d.year(),
                "&7Time: &e" + d.timeLabel() + (d.isNight() ? " &b☽" : " &e☀"),
                "&7Season: &a" + SEASONS[d.season()],
                "",
                "&7One SkyBlock day lasts &e" + dayMin + " &7minutes;",
                "&7a year is &e" + Text.duration(cal.millisPerDay() * 372 / 1000) + "&7."));
        List<CalendarEvent> list = cal.upcoming(INNER.length);
        for (int i = 0; i < list.size(); i++) {
            CalendarEvent e = list.get(i);
            String name = e.name == null ? Text.titleCase(e.id) : e.name;
            long until = cal.millisUntil(e.id);
            List<String> lore = new ArrayList<>();
            lore.add(CalendarMath.isRealtime(e) ? "&8Every " + Text.duration(e.intervalMinutes * 60L) : "&8" + dateLabel(e));
            lore.add("");
            if (until == 0) {
                lore.add("&a&lACTIVE NOW");
                lore.add("&7Ends in: &e" + Text.duration(cal.millisRemaining(e.id) / 1000));
                m.set(INNER[i], Icons.glow(Icons.of(Material.LIME_DYE, "&a" + name, lore)));
            } else {
                lore.add("&7Starts in: &e" + (until < 0 ? "never" : Text.duration(until / 1000)));
                lore.add("&7Lasts: &e" + Text.duration(CalendarMath.length(e, cal.millisPerDay()) / 1000));
                m.set(INNER[i], Icons.of(until < 3_600_000L ? Material.YELLOW_DYE : Material.GRAY_DYE, "&e" + name, lore));
            }
        }
        if (list.isEmpty()) m.set(22, Icons.of(Material.PAPER, "&7No events scheduled", "&7Nothing is on the calendar yet."));
        if (Sky.has(Features.SkyMenu.class)) {
            m.set(m.closeSlot() - 1, Icons.back("SkyBlock Menu"), cl -> Sky.service(Features.SkyMenu.class).open(cl.player()));
        }
    }

    static String dateLabel(CalendarEvent e) {
        String day = SkyDate.ordinal(e.day);
        String when = e.month == 0 ? "Day " + day + " of every month" : SkyDate.MONTHS[e.month - 1] + " " + day;
        if (e.durationDays > 1) when += " (" + e.durationDays + " days)";
        if (e.everyYears > 1) when += ", every " + e.everyYears + " years";
        return when;
    }

    // ---- builtin requirement prefixes -------------------------------------------------------------

    private void registerResolvers() {
        RequirementsImpl req = c.requirements();
        req.register("always", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) { return true; }
            @Override public String describe(String[] parts) { return "&aNothing"; }
        });
        req.register("never", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) { return false; }
            @Override public String describe(String[] parts) { return parts.length > 1 ? "&a" + String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length)) : "&cComing soon"; }
        });
        req.register("perm", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) { return p != null && parts.length > 1 && p.hasPermission(parts[1]); }
            @Override public String describe(String[] parts) { return "&aPermission " + (parts.length > 1 ? parts[1] : "?"); }
        });
        req.register("mode", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) { return p != null && parts.length > 1 && c.profiles().mode(p).equalsIgnoreCase(parts[1]); }
            @Override public String describe(String[] parts) { return "&a" + (parts.length > 1 ? Text.titleCase(parts[1]) : "?") + " profile"; }
        });
        req.register("purse", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (p == null || parts.length < 2) return false;
                double need = Coins.parse(parts[1]);
                return Coins.valid(need) && c.economy().purse(p) >= need;
            }
            @Override public String describe(String[] parts) { return "&6" + (parts.length > 1 ? Coins.format(Coins.parse(parts[1])) : "?") + " coins"; }
        });
        req.register("coop", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                String pid = p == null ? null : c.profiles().profileId(p);
                return pid != null && c.profiles().members(pid).size() > 1;
            }
            @Override public String describe(String[] parts) { return "&aa co-op profile"; }
        });
    }

    // ---- self-tests ------------------------------------------------------------------------------

    /** Plain fixture for the Gson self-test. */
    public static final class GsonFixture {
        public enum Kind { ALPHA, BETA }
        public Kind kind = Kind.ALPHA;
        public EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);
        public Map<Kind, Double> weights = new java.util.HashMap<>();
        public double ratio = 1;
        public GsonFixture() { }
    }

    private void registerSelfTests() {
        CommandServiceImpl cmds = c.commands();
        cmds.selfTest("foundation.coins-format", () -> {
            if (!Coins.format(1234567.5).equals("1,234,567.5")) return "format(1234567.5) = " + Coins.format(1234567.5);
            if (!Coins.format(12).equals("12")) return "format(12) = " + Coins.format(12);
            if (!c.economy().format(-1500.25).equals("-1,500.3") && !c.economy().format(-1500.25).equals("-1,500.2")) return "negative format " + c.economy().format(-1500.25);
            if (Coins.creditTenths(0.3) != 3) return "creditTenths(0.3) = " + Coins.creditTenths(0.3);
            if (Coins.creditTenths(0.05) != 0 || Coins.debitTenths(0.05) != 1) return "rounding direction wrong";
            if (Coins.parse("2.5k") != 2500 || Coins.parse("3m") != 3_000_000) return "parse suffixes wrong";
            if (Coins.valid(-1) || Coins.valid(Double.NaN)) return "negative/NaN accepted";
            return null;
        });
        cmds.selfTest("foundation.calendar-math", () -> {
            long mpd = 1_200_000L;
            long t = (372L + 31 + 1) * mpd + 625_000L; // Year 2, Spring 2nd, 12:30
            SkyDate d = CalendarMath.date(0, mpd, t);
            if (d.year() != 2 || d.month() != 2 || d.day() != 2 || d.hour() != 12 || d.minute() != 30) return "got " + d;
            // research 05 §7.3: epoch 1560275700 (unix s) -> 2026-10-02 is year 517
            SkyDate ref = CalendarMath.date(1_560_275_700_000L, mpd, 1_790_899_200_000L);
            if (ref.year() != 517) return "reference epoch gives year " + ref.year() + " for 2026-10-02, research says 517";
            CalendarEvent da = CalendarEvent.realtime("t", "t", 60, 55, 5);
            long hour = 3_600_000L * 100_000;
            if (!CalendarMath.realtimeActive(da, hour + 57 * 60_000L)) return "60/55/5 not active at :57";
            if (CalendarMath.realtimeActive(da, hour + 30 * 60_000L)) return "60/55/5 active at :30";
            CalendarEvent spooky = CalendarEvent.onDate("s", "s", 8, 29, 3);
            long spookyStart = ((7L * 31) + 28) * mpd;
            if (!CalendarMath.dateActive(spooky, 0, mpd, spookyStart + 1)) return "Autumn 29 event not active on Autumn 29";
            if (!CalendarMath.dateActive(spooky, 0, mpd, spookyStart + 3 * mpd - 1)) return "3-day event not active on its 3rd day";
            if (CalendarMath.dateActive(spooky, 0, mpd, spookyStart + 3 * mpd)) return "3-day event still active on day 4";
            if (CalendarMath.dateUntil(spooky, 0, mpd, spookyStart - 1000) != 1000) return "dateUntil off";
            return null;
        });
        cmds.selfTest("foundation.calendar-events-valid", () -> {
            List<String> bad = new ArrayList<>();
            for (CalendarEvent e : c.calendar().events()) {
                String err = CalendarMath.validate(e);
                if (err != null) bad.add(err);
                if (c.calendar().millisUntil(e.id) < 0) bad.add(e.id + " never occurs");
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        cmds.selfTest("foundation.dial-parse", () -> {
            if (!Integer.valueOf(2500).equals(DialsImpl.parse(DialsImpl.Kind.INTEGER, "2.5k"))) return "integer 2.5k";
            if (DialsImpl.parse(DialsImpl.Kind.INTEGER, "1.5") != null) return "integer accepted 1.5";
            if (!Boolean.TRUE.equals(DialsImpl.parse(DialsImpl.Kind.FLAG, "on"))) return "flag on";
            if (DialsImpl.parse(DialsImpl.Kind.FLAG, "maybe") != null) return "flag accepted maybe";
            if (!Double.valueOf(1000).equals(DialsImpl.parse(DialsImpl.Kind.NUMBER, "1e3"))) return "number 1e3";
            if (!DialsImpl.format(1.7e12).equals("1700000000000")) return "format uses scientific notation: " + DialsImpl.format(1.7e12);
            for (DialsImpl.DialImpl<?> d : c.dials().all()) {
                if (d.description() == null || d.description().isBlank()) return "dial " + d.key() + " has no description";
            }
            return null;
        });
        cmds.selfTest("foundation.requirements-fail-closed", () -> {
            RequirementsImpl r = c.requirements();
            if (r.check(null, "zz_no_such_prefix:1")) return "unknown prefix passed";
            if (!r.check(null, "")) return "empty key failed";
            if (!r.check(null, "always")) return "always failed";
            if (r.check(null, "always&never")) return "& conjunction passed with a false part";
            if (!Text.strip(r.describe("always&never:Soon")).contains("and")) return "describe does not join with 'and': " + r.describe("always&never:Soon");
            return null;
        });
        cmds.selfTest("foundation.gson-lenient", () -> {
            String json = "{\"kind\":\"beta\",\"counts\":{\"ALPHA\":3,\"REMOVED\":9},\"weights\":{\"gamma\":1.0,\"BETA\":2.5},\"ratio\":NaN}";
            GsonFixture f = Gsons.PROFILE.fromJson(json, GsonFixture.class);
            if (f.kind != GsonFixture.Kind.BETA) return "case-insensitive enum failed: " + f.kind;
            if (f.counts.size() != 1 || f.counts.get(GsonFixture.Kind.ALPHA) != 3) return "EnumMap unknown key not dropped: " + f.counts;
            if (f.weights.size() != 1 || f.weights.get(GsonFixture.Kind.BETA) != 2.5) return "Map<Enum> unknown key not dropped: " + f.weights;
            if (!Double.isNaN(f.ratio)) return "NaN not read";
            String back = Gsons.PROFILE.toJson(f);
            if (!back.contains("\"BETA\"")) return "round trip lost the enum: " + back;
            GsonFixture unk = Gsons.PROFILE.fromJson("{\"kind\":\"NOPE\"}", GsonFixture.class);
            if (unk.kind != null) return "unknown enum did not read as null";
            return null;
        });
        cmds.selfTest("foundation.db-roundtrip", () -> {
            try {
                Integer v = c.db().query(con -> {
                    try (var st = con.createStatement(); var rs = st.executeQuery("SELECT version FROM schema_versions WHERE module = 'core'")) {
                        return rs.next() ? rs.getInt(1) : -1;
                    }
                }).get(3, TimeUnit.SECONDS);
                if (v == null || v < 1) return "core schema version is " + v;
                String mode = c.db().query(con -> {
                    try (var st = con.createStatement(); var rs = st.executeQuery("PRAGMA journal_mode")) { return rs.next() ? rs.getString(1) : "?"; }
                }).get(3, TimeUnit.SECONDS);
                if (!"wal".equalsIgnoreCase(mode)) return "journal_mode is " + mode + ", expected wal";
                Integer nested = c.db().transaction(con -> c.db().transaction(con2 -> 7).join()).get(3, TimeUnit.SECONDS);
                if (nested == null || nested != 7) return "nested transaction returned " + nested;
                return null;
            } catch (Exception e) {
                return "database did not answer: " + e;
            }
        });
        // ---- wave 5 API additions -----------------------------------------------------------------
        cmds.selfTest("api.skilltype-hunting", () -> {
            var h = com.skyclone.api.skill.SkillType.HUNTING;
            if (h.maxLevel != 50 || h.wisdom != com.skyclone.api.stat.Stat.HUNTING_WISDOM) return "HUNTING is " + h.maxLevel + "/" + h.wisdom;
            if (com.skyclone.api.skill.SkillType.FORAGING.maxLevel != 57) return "FORAGING max should be the data's 57";
            if (com.skyclone.api.stat.Stat.parse("Hunting Fortune") != com.skyclone.api.stat.Stat.HUNTING_FORTUNE) return "Stat.parse(Hunting Fortune) failed";
            if (com.skyclone.api.skill.SkillType.parse("hunting") != h) return "SkillType.parse(hunting) failed";
            return null;
        });
        cmds.selfTest("api.feature-noop-defaults", () -> {
            // a missing provider must answer with the caller's default, not false/""
            if (!Sky.has(com.skyclone.api.feature.Features.Settings.class)) {
                var st = Sky.service(com.skyclone.api.feature.Features.Settings.class);
                if (!st.flag(null, "x", true)) return "no-op Settings.flag ignored def=true";
                if (!"d".equals(st.text(null, "x", "d"))) return "no-op Settings.text ignored def";
            }
            if (!Sky.has(com.skyclone.api.feature.Features.Bank.class)
                    && Sky.service(com.skyclone.api.feature.Features.Bank.class).bitsMultiplier(null) != 1.0) return "no-op Bank.bitsMultiplier != 1";
            if (!Sky.has(com.skyclone.api.feature.Features.Soulflow.class)
                    && Sky.service(com.skyclone.api.feature.Features.Soulflow.class).consume(null, 1, "selftest")) return "no-op Soulflow.consume succeeded";
            if (!Sky.has(com.skyclone.api.feature.Features.Rift.class)
                    && Sky.service(com.skyclone.api.feature.Features.Rift.class).spendMotes(null, 1, "selftest")) return "no-op Rift.spendMotes succeeded";
            return null;
        });
        cmds.selfTest("api.dungeon-xp-event-shape", () -> {
            var ev = new com.skyclone.api.event.SkyEvents.DungeonXpGain(null, "catacombs", "MAGE", 7, true, 1000, null);
            if (!ev.catacombs() || !"run".equals(ev.source) || ev.floor != 7 || !ev.master) return "constructor/fields wrong";
            ev.amount *= 1.1;
            if (Math.abs(ev.amount - 1100) > 1e-9) return "amount is not mutable";
            if (new com.skyclone.api.event.SkyEvents.DungeonXpGain(null, "TANK", "TANK", 1, false, 5, "admin").catacombs()) return "class track read as catacombs";
            return null;
        });
        cmds.selfTest("foundation.commands-described", () -> {
            List<String> bad = new ArrayList<>();
            for (SkyCommand sc : cmds.commands()) if (sc.description == null || sc.description.isBlank()) bad.add("/" + sc.name);
            for (CommandServiceImpl.AdminSub a : cmds.adminSubs()) if (a.description().isBlank()) bad.add("/sb " + a.sub());
            if (!cmds.lifecycleFired()) return "Paper's command lifecycle event has not fired - no command is registered with the server";
            return bad.isEmpty() ? null : "no description: " + String.join(", ", bad);
        });
        cmds.selfTest("foundation.profiles-loaded", () -> {
            List<String> bad = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!c.profiles().isLoaded(p)) bad.add(p.getName() + " not loaded");
                else if (!c.profiles().isReady(p)) bad.add(p.getName() + " loaded but ProfileReady not fired");
                for (String line : c.profiles().describe(p)) if (line.startsWith("POISONED")) bad.add(p.getName() + ": " + line);
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        cmds.selfTest("foundation.menus-build", () -> {
            if (!Sky.has(MenuService.class)) return null; // ui core missing: nothing to build
            for (Player p : Bukkit.getOnlinePlayers()) {
                String err = c.profileCommands().menuBuilds(p);
                if (err != null) return err;
                try { calendarMenu(p).redraw(); } catch (Throwable t) { return "calendar menu threw " + t; }
                return null;
            }
            return null; // needs an online player to build a head icon
        });
    }
}
