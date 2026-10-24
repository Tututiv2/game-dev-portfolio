package com.aisky.showcontrol;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.mob.MobDef;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /show - the console/RCON/op control surface (CONTRACT §4).
 * <pre>
 *  /show reset [account...]                 wipe the cast's profiles (fresh profile on next join)
 *  /show start &lt;minutes&gt;                    episode_start, starts the clock
 *  /show stop                               episode_end
 *  /show freeze on|off                      players cannot move or be hurt; world ticks frozen
 *  /show mark &lt;label...&gt;                    mark{label} on the feed (edit marker)
 *  /show camera &lt;account&gt;                   designate the spectator camera account (hidden)
 *  /show cam orbit|follow|pushin|stop [target] [seconds] [radius]   fallback camera
 *  /show ruleset &lt;name&gt;|list|off            apply SkyClone dials from rulesets.yml
 *  /show status                             one-screen summary
 *  /show test kill|boss|hit|die &lt;player&gt; [arg]   drive REAL SkyClone code paths (build gate)
 *  /show emit &lt;type&gt; [drama] [text...]      synthetic event (overlay/director development)
 * </pre>
 */
final class ShowCommand implements TabExecutor {

    private final ShowControlPlugin plugin;

    ShowCommand(ShowControlPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command command, String label, String[] a) {
        if (a.length == 0) { usage(s); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(a, 1, a.length);
        try {
            switch (sub) {
                case "reset" -> plugin.resets().reset(s, List.of(rest));
                case "start" -> {
                    int mins = rest.length > 0 ? Integer.parseInt(rest[0]) : 30;
                    plugin.episode().start(mins);
                    s.sendMessage("Episode started: " + mins + " min.");
                }
                case "stop" -> {
                    if (!plugin.episode().running) s.sendMessage("No episode running.");
                    else { plugin.episode().stop("stopped"); s.sendMessage("Episode stopped."); }
                }
                case "freeze" -> {
                    boolean on = rest.length == 0 || !rest[0].equalsIgnoreCase("off");
                    plugin.episode().freeze(on);
                    s.sendMessage("Freeze " + (on ? "ON" : "OFF"));
                }
                case "mark" -> {
                    String lbl = rest.length == 0 ? "mark" : String.join(" ", rest);
                    long id = plugin.bus().ev("mark").drama(5).data("label", lbl).text("Marker: " + lbl).emit();
                    s.sendMessage("Marked '" + lbl + "' (event " + id + ")");
                }
                case "camera" -> {
                    if (rest.length == 0) { s.sendMessage("Camera account: " + plugin.camera().account()); return true; }
                    plugin.camera().setAccount(rest[0]);
                    s.sendMessage("Camera account is now " + rest[0] + (Bukkit.getPlayerExact(rest[0]) == null ? " (offline; prepared on join)" : " (spectator, hidden)"));
                }
                case "cam" -> cam(s, rest);
                case "ruleset" -> ruleset(s, rest);
                case "status" -> status(s);
                case "test" -> test(s, rest);
                case "emit" -> emit(s, rest);
                default -> usage(s);
            }
        } catch (NumberFormatException e) {
            s.sendMessage("Not a number: " + e.getMessage());
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage("/show reset [acct..] | start <min> | stop | freeze on|off | mark <label> | camera <acct> | "
                + "cam orbit|follow|pushin|stop [target] [sec] [radius] | ruleset <name>|list|off | status | "
                + "test kill|boss|hit|die <player> [arg] | emit <type> [drama] [text]");
    }

    private void cam(CommandSender s, String[] a) {
        if (a.length == 0) { s.sendMessage("Camera: " + plugin.camera().status()); return; }
        if (a[0].equalsIgnoreCase("stop")) { plugin.camera().stop(); s.sendMessage("Camera stopped."); return; }
        if (a.length < 2) { s.sendMessage("/show cam " + a[0] + " <target> [seconds] [radius]"); return; }
        double secs = a.length > 2 ? Double.parseDouble(a[2]) : 0;
        Double radius = a.length > 3 ? Double.parseDouble(a[3]) : null;
        s.sendMessage(plugin.camera().start(a[0], a[1], secs, radius));
    }

    private void ruleset(CommandSender s, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("list")) {
            s.sendMessage("Active ruleset: " + (plugin.rulesets().active == null ? "(none)" : plugin.rulesets().active));
            for (String n : plugin.rulesets().names()) s.sendMessage(" - " + n + ": " + plugin.rulesets().description(n));
            return;
        }
        for (String line : plugin.rulesets().apply(a[0])) s.sendMessage(line);
    }

    private void status(CommandSender s) {
        Episode e = plugin.episode();
        s.sendMessage("Episode: " + (e.running ? "RUNNING " + e.elapsedSeconds() + "s / " + e.minutes + "min" : "stopped")
                + (e.frozen ? " [FROZEN]" : ""));
        s.sendMessage("Ruleset: " + (plugin.rulesets().active == null ? "(none)" : plugin.rulesets().active)
                + "  mob_coin_mult=" + plugin.rulesets().mobCoinMult + "  magic_find_bonus=" + plugin.rulesets().magicFindBonus);
        s.sendMessage("Camera: " + plugin.camera().account() + " - " + plugin.camera().status());
        s.sendMessage("Cast: " + String.join(", ", plugin.config().cast) + "  pending inventory wipes: " + plugin.resets().pending().size());
        s.sendMessage("Feed: last id " + plugin.bus().lastId() + "  firsts taken: " + plugin.memory().firsts());
        s.sendMessage("HTTP: http://" + plugin.config().httpHost + ":" + plugin.config().httpPort + "  TPS " + plugin.snapshots().tps());
    }

    // ---- test helpers (real code paths) -------------------------------------------------------

    private void test(CommandSender s, String[] a) {
        if (!plugin.config().allowTestCommands) { s.sendMessage("Test commands are disabled (allow-test-commands: false)."); return; }
        if (a.length < 2) { s.sendMessage("/show test kill|boss|hit|die <player> [mobId|fraction]"); return; }
        Player p = Bukkit.getPlayerExact(a[1]);
        if (p == null) { s.sendMessage(a[1] + " is not online."); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "kill" -> {
                String id = a.length > 2 ? a[2] : "hub_graveyard_zombie";
                LivingEntity mob = spawnNear(s, p, id, 3.0);
                if (mob == null) return;
                // A real player hit through SkyClone's pipeline, big enough to kill: MobKill fires with this killer.
                double dealt = Sky.combat().playerAttack(p, mob, p.getInventory().getItemInMainHand(), DamageContext.Kind.MELEE, 1e9);
                if (mob.isValid() && Sky.mobs().health(mob) > 0) Sky.mobs().setHealth(mob, 0);
                s.sendMessage("test kill: " + id + " hit for " + Math.round(dealt) + " by " + p.getName());
            }
            case "boss" -> {
                String id = a.length > 2 ? a[2] : pickBoss();
                if (id == null) { s.sendMessage("No boss-flagged mob registered."); return; }
                LivingEntity mob = spawnNear(s, p, id, 6.0);
                if (mob == null) return;
                s.sendMessage("test boss: spawned " + id + " (removed again in 5 s)");
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (mob.isValid()) Sky.mobs().remove(mob); }, 100L);
            }
            case "hit" -> {
                double frac = a.length > 2 ? Double.parseDouble(a[2]) : 0.9;
                double max = Sky.vitals().maxHealth(p);
                double hp = Sky.vitals().health(p);
                // TRUE damage through SkyClone's pipeline (fires SkyEvents.Damage), never quite lethal.
                double amount = Math.min(max * frac, Math.max(0, hp - 1));
                double dealt = Sky.combat().damagePlayer(p, amount, null, DamageContext.Kind.TRUE);
                s.sendMessage("test hit: " + p.getName() + " took " + Math.round(dealt) + " (" + Math.round(Sky.vitals().health(p)) + "/" + Math.round(max) + " left)");
            }
            case "die" -> {
                Sky.vitals().kill(p, a.length > 2 ? a[2] : "show test");
                s.sendMessage("test die: " + p.getName() + " was killed through SkyClone's death path");
            }
            default -> s.sendMessage("/show test kill|boss|hit|die <player> [arg]");
        }
    }

    private LivingEntity spawnNear(CommandSender s, Player p, String mobId, double dist) {
        if (Sky.mobs().def(mobId) == null) { s.sendMessage("Unknown mob id " + mobId); return null; }
        Location at = p.getLocation().clone();
        Vector dir = at.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(1, 0, 0);
        at.add(dir.normalize().multiply(dist));
        try {
            LivingEntity le = Sky.mobs().spawn(mobId, at);
            if (le == null) s.sendMessage("Spawn of " + mobId + " was refused by a listener (safe zone?)");
            return le;
        } catch (RuntimeException e) {
            s.sendMessage("Spawn failed: " + e.getMessage());
            return null;
        }
    }

    private static String pickBoss() {
        // Prefer bosses with NO scripted behaviour (nothing can misfire outside their arena), then any.
        for (String id : List.of("spooky_headless_rider", "ch_boss_corleone", "den_broodmother_12")) {
            MobDef d = Sky.mobs().def(id);
            if (d != null && d.has("boss")) return id;
        }
        return Sky.mobs().all().stream()
                .filter(d -> d.has("boss") && d.id != null)
                .min(Comparator.comparingDouble((MobDef d) -> d.health))
                .map(d -> d.id).orElse(null);
    }

    private void emit(CommandSender s, String[] a) {
        if (a.length == 0) { s.sendMessage("/show emit <type> [drama] [text...]"); return; }
        int drama = 3;
        int textFrom = 1;
        if (a.length > 1) {
            try { drama = Integer.parseInt(a[1]); textFrom = 2; } catch (NumberFormatException ignored) { }
        }
        String text = a.length > textFrom ? String.join(" ", Arrays.copyOfRange(a, textFrom, a.length)) : "synthetic " + a[0];
        long id = plugin.bus().ev(a[0]).drama(drama).text(text).data("synthetic", true).emit();
        s.sendMessage("Emitted synthetic " + a[0] + " (event " + id + ")");
    }

    // ---- tab -------------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("reset", "start", "stop", "freeze", "mark", "camera", "cam", "ruleset", "status", "test", "emit"));
        else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "freeze" -> out.addAll(List.of("on", "off"));
                case "cam" -> out.addAll(List.of("orbit", "follow", "pushin", "stop"));
                case "ruleset" -> { out.addAll(plugin.rulesets().names()); out.add("list"); out.add("off"); }
                case "test" -> out.addAll(List.of("kill", "boss", "hit", "die"));
                case "reset", "camera" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                case "start" -> out.addAll(List.of("30", "10", "5"));
                default -> { }
            }
        } else if (a.length == 3 && (a[0].equalsIgnoreCase("cam") || a[0].equalsIgnoreCase("test"))) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        String pre = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(pre));
        return out;
    }
}
