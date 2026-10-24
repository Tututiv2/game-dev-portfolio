package com.skyclone.mod.rift;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.world.IslandDef;
import com.skyclone.mod.rift.gen.RiftGenerator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Rift (research 08 sec 5, research 03 sec 19): a second dimension with its own inventory, its own
 * stat sheet, a draining Rift Time, Motes, 52 Riddle Souls, 8 Timecharms behind area gates, puzzles,
 * three Rift bosses and the vampire-slayer hook.
 *
 * <p>Pieces: {@link RiftTravel} (entry, exit, the timer), {@link RiftInventory} (the crash-safe swap),
 * {@link RiftStats} (the Rift stat sheet), {@link RiftCombat} (Rift Damage / hearts / kills),
 * {@link RiftWorldState} (souls, orbs, eggs, gathering), {@link RiftPuzzles} (timecharm puzzles),
 * {@link RiftNpcs} (quests and shops), {@link RiftMenus}, {@link RiftCommands}, {@link RiftSelfTests}.
 */
public final class RiftModule implements GameModule {

    static RiftModule INSTANCE;

    RiftDials dials;
    final Map<UUID, RiftSession> sessions = new ConcurrentHashMap<>();
    RiftStats stats;
    RiftEconomy economy;
    RiftTravel travel;
    RiftCombat combat;
    RiftWorldState world;
    RiftPuzzles puzzles;
    RiftNpcs npcs;
    RiftMenus menus;
    RiftGallery gallery;
    RiftSplitSteal splitSteal;
    RiftOdonata odonata;
    RiftRiddles riddles;
    RiftCat cat;
    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override public String id() { return "rift"; }

    @Override
    public void enable() {
        INSTANCE = this;
        dials = new RiftDials();
        Sky.items().loadJson("rift", "items.json");
        Sky.mobs().loadJson("rift", "mobs.json");
        Sky.recipes().loadJson("rift", "recipes.json");
        Sky.mobs().loadSpawnRules("rift", "spawns.json");

        IslandDef def = IslandDef.of(RiftCatalog.ISLAND, "The Rift", new RiftGenerator());
        def.fixedTime = 13000;            // a permanent violet dusk
        Sky.worlds().registerIsland(def);
        Sky.worlds().registerWarp("rift", RiftCatalog.ISLAND, "spawn", "The Rift", "rift:pass");

        economy = new RiftEconomy(this);
        stats = new RiftStats(this);
        menus = new RiftMenus(this);
        travel = new RiftTravel(this);
        combat = new RiftCombat(this);
        world = new RiftWorldState(this);
        puzzles = new RiftPuzzles(this);
        npcs = new RiftNpcs(this);
        gallery = new RiftGallery(this);
        splitSteal = new RiftSplitSteal(this);
        odonata = new RiftOdonata(this);
        riddles = new RiftRiddles(this);
        cat = new RiftCat(this);
        // wave 5: Motes for other modules (rift Request 6: slayer's Riftstalker fees can be Motes now)
        Sky.provide(Features.Rift.class, new Features.Rift() {
            @Override public long motes(Player p) { RiftData d = data(p); return d == null ? 0 : d.motes; }
            @Override public boolean spendMotes(Player p, long amount, String reason) {
                if (amount < 0) return false;
                boolean ok = takeMotes(p, amount);
                if (ok && amount > 0 && reason != null) Sky.log().fine("[rift] " + p.getName() + " spent " + amount + " Motes: " + reason);
                return ok;
            }
            @Override public void addMotes(Player p, long amount, String reason) { RiftModule.this.addMotes(p, amount, reason); }
        });

        Sky.requirements().register("rift", new RiftRequirements(this));
        Sky.stats().addProvider("rift", stats::contribute);
        Bukkit.getPluginManager().registerEvents(travel, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(combat, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(world, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(puzzles, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(odonata, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(riddles, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new RiftEffects(this), Sky.plugin());
        riddles.register();
        cat.register();
        combat.register();
        world.register();
        npcs.register();
        RiftCommands.register(this);
        RiftSelfTests.register(this);
        Sky.vitals().actionBarSegment("rift", 50, travel::actionBar);
        Sky.sidebar().section("rift", 300, travel::sidebar);

        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), travel::tickSecond, 20L, 20L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), world::tickFast, 10L, 10L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), puzzles::tick, 5L, 5L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), world::tickSlow, 100L, 100L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> { gallery.tick(); splitSteal.tick(); }, 40L, 20L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), odonata::tick, 41L, 4L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> { riddles.tick(); cat.tick(); }, 43L, 5L));
    }

    @Override
    public void postEnable() {
        economy.load();
        npcs.placeHubCopy();
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), npcs::placeHubCopy, 200L);
    }

    @Override
    public void disable() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        // give every visitor their main inventory back before the server saves them
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (RiftInventory.inside(p)) {
                try { travel.endSession(p, "the server is restarting", false); } catch (Throwable t) { Sky.log().warning("[rift] exit on disable failed for " + p.getName() + ": " + t); }
            }
        }
        world.restoreAll();
        puzzles.shutdown();
        riddles.shutdown();
        cat.restoreAll();
        gallery.shutdown();
        combat.shutdown();
    }

    // ---- shared helpers ---------------------------------------------------------------------------

    RiftData data(Player p) {
        if (p == null || !Sky.profiles().isReady(p)) return null;
        return Sky.profiles().member(p, "rift", RiftData.class);
    }

    boolean inRift(Player p) { return RiftCatalog.ISLAND.equals(Sky.worlds().islandOf(p.getWorld())); }

    RiftSession session(Player p) { return sessions.get(p.getUniqueId()); }

    /** Credit motes (lifetime too) and show them. */
    void addMotes(Player p, long n, String why) {
        RiftData d = data(p);
        if (d == null || n <= 0) return;
        d.motes += n;
        d.lifetimeMotes += n;
        RiftSession s = session(p);
        if (s != null) s.motesThisVisit += n;
        if (why != null) Sky.vitals().flash(p, "&d+" + n + " Motes &7(" + why + ")", 30);
    }

    boolean takeMotes(Player p, long n) {
        RiftData d = data(p);
        if (d == null || n < 0 || d.motes < n) return false;
        d.motes -= n;
        return true;
    }

    /** One-time SkyBlock XP for Rift progress (progression files "rift:" keys under the Rift Guide task). */
    void award(Player p, String key, int xp, String reason) {
        try { Sky.service(Features.SkyLevel.class).award(p, "rift:" + key, xp, reason); } catch (RuntimeException ignored) { }
    }
}
