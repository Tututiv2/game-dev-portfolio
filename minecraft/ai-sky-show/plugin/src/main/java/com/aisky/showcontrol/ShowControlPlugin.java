package com.aisky.showcontrol;

import com.skyclone.api.Sky;
import com.skyclone.api.stat.Stat;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ShowControl - the "Can 4 AIs Beat Hypixel SkyBlock?" rig's server half. A separate plugin that
 * depends on SkyClone and only USES its public API (com.skyclone.api) - it never patches SkyClone.
 *
 * <p>Feeds the orchestrator: an SSE event feed of everything worth showing, a structured /state per
 * agent, a console-command endpoint, the episode clock, a fallback camera, and rulesets that tune
 * SkyClone's own dials for a 30-minute sprint. See plugin/README.md.
 */
public final class ShowControlPlugin extends JavaPlugin {

    private ShowConfig config;
    private EventBus bus;
    private EpisodeMemory memory;
    private Episode episode;
    private GatherAggregator gather;
    private StateSnapshots snapshots;
    private CameraFallback camera;
    private CommandRunner runner;
    private Rulesets rulesets;
    private ProfileReset resets;
    private HttpApi http;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new java.io.File(getDataFolder(), "rulesets.yml").exists()) saveResource("rulesets.yml", false);
        config = new ShowConfig(getConfig());
        bus = new EventBus(config.ringSize);
        memory = new EpisodeMemory();
        runner = new CommandRunner(this, config.feedbackSender);
        rulesets = new Rulesets(this, runner);
        rulesets.restore();
        episode = new Episode(this);
        gather = new GatherAggregator(this);
        snapshots = new StateSnapshots(this);
        camera = new CameraFallback(this);
        resets = new ProfileReset(this);

        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        PluginCommand cmd = getCommand("show");
        if (cmd != null) {
            ShowCommand sc = new ShowCommand(this);
            cmd.setExecutor(sc);
            cmd.setTabCompleter(sc);
        }

        // Ruleset knob: Magic Find for the cast, through SkyClone's stat pipeline.
        try {
            Sky.stats().addProvider("showcontrol", (p, into) -> {
                double mf = rulesets.magicFindBonus;
                if (mf > 0 && config.tracked(p)) into.add(Stat.MAGIC_FIND, mf);
            });
        } catch (RuntimeException e) {
            getLogger().warning("could not register the showcontrol stat provider: " + e);
        }

        var sched = getServer().getScheduler();
        sched.runTaskTimer(this, snapshots::tick, 20L, config.snapshotTicks);
        sched.runTaskTimer(this, () -> { episode.tick(); gather.tick(); }, 20L, 20L);
        sched.runTaskTimer(this, camera, 1L, 1L);

        http = new HttpApi(this);
        try {
            http.start();
        } catch (Exception e) {
            getLogger().severe("ShowControl HTTP could not bind " + config.httpHost + ":" + config.httpPort + " - " + e
                    + " (the game keeps running; the orchestrator will not see the feed)");
        }
        bus.ev("mark").drama(0).data("label", "showcontrol_enabled").text("ShowControl is online").emit();
    }

    @Override
    public void onDisable() {
        if (http != null) http.stop();
        try { Sky.stats().removeProvider("showcontrol"); } catch (RuntimeException ignored) { }
        try { if (episode != null && episode.frozen) episode.freeze(false); } catch (RuntimeException ignored) { }
    }

    /** Emits first_of_kind once per key per episode. */
    void first(String key, Player p, String what, int drama, Location at) {
        if (p == null || !memory.claimFirst(key, p.getName())) return;
        bus.ev("first_of_kind").player(p.getName()).drama(drama).loc(at)
                .data("what", what).data("key", key)
                .text(p.getName() + " claims a first: " + what).emit();
    }

    ShowConfig config() { return config; }
    EventBus bus() { return bus; }
    EpisodeMemory memory() { return memory; }
    Episode episode() { return episode; }
    GatherAggregator gather() { return gather; }
    StateSnapshots snapshots() { return snapshots; }
    CameraFallback camera() { return camera; }
    CommandRunner runner() { return runner; }
    Rulesets rulesets() { return rulesets; }
    ProfileReset resets() { return resets; }
}
