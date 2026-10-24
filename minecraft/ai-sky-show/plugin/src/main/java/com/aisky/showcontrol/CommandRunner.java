package com.aisky.showcontrol;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs a console command on the MAIN thread and captures what it printed while it ran
 * (Paper's {@code Server#createCommandSender(feedback)}). Output printed later (after a DB future)
 * is not captured - same limit as RCON, see SkyClone CORE_GUIDE §0.11.
 */
final class CommandRunner {

    record Result(boolean ok, String output) {}

    private final Plugin plugin;
    private final boolean feedback;

    CommandRunner(Plugin plugin, boolean feedback) {
        this.plugin = plugin;
        this.feedback = feedback;
    }

    /** Main thread only. */
    Result runNow(String command) {
        String cmd = command == null ? "" : command.trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (cmd.isEmpty()) return new Result(false, "empty command");
        List<String> lines = new ArrayList<>();
        CommandSender sender = feedback
                ? Bukkit.createCommandSender(c -> { synchronized (lines) { lines.add(Names.plain(c)); } })
                : Bukkit.getConsoleSender();
        boolean ok;
        try {
            ok = Bukkit.dispatchCommand(sender, cmd);
        } catch (RuntimeException e) {
            synchronized (lines) { lines.add("error: " + e); }
            ok = false;
        }
        String out;
        synchronized (lines) { out = String.join("\n", lines); }
        if (!feedback && out.isEmpty()) out = "(console sender: output went to the server log)";
        return new Result(ok, out);
    }

    /** From any thread: hop to the main thread, wait up to {@code timeoutMs}. */
    Result runFromAnyThread(String command, long timeoutMs) {
        if (Bukkit.isPrimaryThread()) return runNow(command);
        try {
            Future<Result> f = Bukkit.getScheduler().callSyncMethod(plugin, () -> runNow(command));
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return new Result(false, "timed out waiting for the main thread (" + timeoutMs + " ms)");
        } catch (Exception e) {
            return new Result(false, "error: " + e);
        }
    }
}
