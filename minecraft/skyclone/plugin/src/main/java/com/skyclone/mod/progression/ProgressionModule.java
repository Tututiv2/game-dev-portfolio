package com.skyclone.mod.progression;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * progression: SkyBlock Level (Features.SkyLevel), the objective chain (Features.Objectives), Fairy
 * Souls, the Bestiary (Features.Bestiary), the Museum (Features.Museum) and Century Cakes.
 * See docs/handoff/progression.md.
 */
public final class ProgressionModule implements GameModule, Listener {

    private final List<BukkitTask> tasks = new ArrayList<>();
    private SoulboundGuard soulbound;
    private int tickCounter;

    @Override public String id() { return "progression"; }

    @Override public void enable() {
        Prog.registerDials();
        Prog.levelsFile = Prog.resource("levels.json", Catalogs.LevelsFile.class);
        Prog.bestiaryFile = Prog.resource("bestiary.json", Catalogs.BestiaryFile.class);
        Prog.museumFile = Prog.resource("museum.json", Catalogs.MuseumFile.class);
        Prog.objectivesFile = Prog.resource("objectives.json", Catalogs.ObjectivesFile.class);
        try {
            Prog.guidesFile = Prog.resource("island_guides.json", Catalogs.GuidesFile.class);
        } catch (Throwable t) {
            Sky.log().warning("[progression] island_guides.json: " + t);
            Prog.guidesFile = new Catalogs.GuidesFile();
        }
        try {
            com.google.gson.JsonObject misc = Prog.resource("misc.json", com.google.gson.JsonObject.class);
            if (misc.has("fairySoulTexture")) Prog.fairyTexture = misc.get("fairySoulTexture").getAsString();
        } catch (Throwable t) {
            Sky.log().warning("[progression] misc.json: " + t);
        }

        Sky.items().loadJson("progression", "items.json");

        Prog.levels = new SkyLevels();
        Prog.objectives = new Objectives();
        Prog.fairy = new FairySouls();
        Prog.bestiary = new Bestiary();
        Prog.museum = new Museum();
        Prog.cakes = new Cakes();
        Prog.scrolls = new TravelScrolls();
        Prog.islandGuides = new IslandGuides();
        Prog.perksSys = new Perks();
        Prog.xpGuide = new XpGuide();
        soulbound = new SoulboundGuard();

        Sky.provide(Features.SkyLevel.class, Prog.levels);
        Sky.provide(Features.Objectives.class, Prog.objectives);
        Sky.provide(Features.Bestiary.class, Prog.bestiary);
        Sky.provide(Features.Museum.class, Prog.museum);

        Sky.requirements().register("sblevel", Prog.levels.resolver());
        Sky.requirements().register("quest", Prog.objectives.resolver());
        Sky.requirements().register("bestiary", Prog.bestiary.resolver());
        Sky.requirements().register("fairysouls", Prog.fairy.resolver());
        Sky.requirements().register("museum", Prog.museum.resolver());
        Sky.requirements().register("museum_milestone", Prog.museum.milestoneResolver());
        Sky.requirements().register("museummilestone", Prog.museum.milestoneResolver());

        Sky.stats().addProvider("progression.sblevel", Prog.levels::contribute);
        Sky.stats().addProvider("progression.fairy", Prog.fairy::contribute);
        Sky.stats().addProvider("progression.bestiary", Prog.bestiary::contribute);
        Sky.stats().addProvider("progression.cakes", Prog.cakes::contribute);
        Sky.stats().addProvider("progression.perks", Prog.perksSys::contribute);
        Sky.combat().addModifier("progression.bestiary", 350, Prog.bestiary::modify);
        // Extra Infliction (SkyBlock Level 75): a final multiplier, so after every other source (900 = final)
        Sky.combat().addModifier("progression.infliction", 900, Prog.perksSys::modify);
        // Bestiary "+X Magic Find vs that mob" (research 08 6.1) through core's per-target loot hook (wave 5)
        Sky.loot().addMagicFindModifier("progression.bestiary", Prog.bestiary::magicFindVs);
        soulbound.registerLore();

        Prog.fairy.registerNpc();
        Prog.museum.registerNpc();
        Prog.cakes.registerEvents();

        // Lowest priority on purpose: the frame head is 9 lines of 15, so the always-present objective
        // at 100 left 1 line and silently hid every 2-line section (slayer quest, powder, contest,
        // pests). As the filler it shows whenever there is room, like the reference. (Playtest "loops".)
        Sky.sidebar().section("progression.objective", 900, p -> Prog.objectives.sidebar(p));
        // Inside a Hub district with an unfinished District Guide, hub's "Guide: <district> >> <step>" (350) took
        // the only free sidebar slot and the Objective vanished exactly where objectives happen (wave-3 playtest).
        // There the Objective moves just above the guide and carries one guide line; the 900 filler stands down.
        // 449 = just ahead of hub's guide (450) and behind every live section (festival, fishing at 400): at 349 the
        // tutorial pair hid the Spooky Festival's candy count in the Graveyard (wave-4 endgame playtest).
        Sky.sidebar().section("progression.objective.district", 449, p -> Prog.objectives.districtSidebar(p));
        Sky.sidebar().tabWidget("progression.progress", 130, this::tabLines);

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(this, Sky.plugin());
        pm.registerEvents(Prog.objectives, Sky.plugin());
        pm.registerEvents(Prog.bestiary, Sky.plugin());
        pm.registerEvents(Prog.cakes, Sky.plugin());
        pm.registerEvents(Prog.fairy, Sky.plugin());
        pm.registerEvents(Prog.scrolls, Sky.plugin());
        pm.registerEvents(Prog.islandGuides, Sky.plugin());
        pm.registerEvents(soulbound, Sky.plugin());

        registerCommands();
        ProgressionAdmin.register();
        ProgressionTests.register();
    }

    @Override public void postEnable() {
        Prog.cakes.load();
        Prog.bestiary.index();
        Prog.fairy.rescan();
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::fastTick, 20L, 5L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> Prog.fairy.spin(), 40L, 10L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> Prog.fairy.watchdog(), 60L, 40L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            Prog.fairy.rescan();
            Prog.bestiary.index();
        }, 200L, 1200L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::pollObjectives, 40L, 10L));
    }

    @Override public void disable() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        if (Prog.fairy != null) Prog.fairy.removeDisplays();
    }

    private void fastTick() {
        tickCounter++;
        try { Prog.fairy.tick(tickCounter % 2 == 0); }
        catch (Throwable t) { Sky.log().warning("[progression] fairy tick: " + t); }
    }

    private int pollPhase;

    private void pollObjectives() {
        int every = Math.max(10, Prog.objPollTicks.get());
        pollPhase += 10;
        if (pollPhase < every) return;
        pollPhase = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { Prog.objectives.poll(p); }
            catch (Throwable t) { Sky.log().warning("[progression] objective poll for " + p.getName() + ": " + t); }
            try { Prog.islandGuides.poll(p); }
            catch (Throwable t) { Sky.log().warning("[progression] island guide poll for " + p.getName() + ": " + t); }
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        Prog.levels.onReady(p);
        Prog.objectives.onReady(p);
        Prog.fairy.onReady(p);
        Prog.museum.syncAwards(p);
        Prog.perksSys.checkTome(p);
        Sky.stats().invalidate(p);
        if (e.firstJoin && Prog.objEnabled.get()) {
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (!Prog.ready(p)) return;
                Catalogs.ObjectiveDef cur = Prog.objectives.currentDef(p);
                if (cur == null) return;
                com.skyclone.api.ui.Msg.send(p, "&eYour first objective: &f" + cur.text);
                com.skyclone.api.ui.Msg.send(p, "&7" + cur.hint + " &8(/questlog)");
            }, 60L);
        }
    }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) {
        Prog.fairy.onQuit(e.player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Prog.levels.onQuit(e.getPlayer());
        Prog.objectives.onQuit(e.getPlayer());
        Prog.fairy.onQuit(e.getPlayer());
        Prog.scrolls.forget(e.getPlayer());
        Prog.islandGuides.onQuit(e.getPlayer());
        if (soulbound != null) soulbound.forget(e.getPlayer());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> Prog.fairy.applyVisibility(e.getPlayer()), 5L);
    }

    @EventHandler
    public void onBuilt(SkyEvents.IslandBuilt e) {
        Prog.fairy.rescan();
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> Prog.fairy.watchdog(), 20L);
    }

    // ---- tab ------------------------------------------------------------------------------------------------

    private List<String> tabLines(Player p) {
        if (!Prog.ready(p)) return List.of();
        List<String> l = new ArrayList<>();
        l.add("&b&lProgress");
        PData.Level d = Prog.level(p);
        int lvl = PMath.level(d.xp, Prog.levelsFile.xpPerLevel);
        String em = Prog.levels.emblemSymbol(p);
        l.add("&fSB Level: " + PMath.levelBracket(lvl) + (em.isEmpty() ? "" : " " + PMath.levelColor(lvl) + em)
                + " &7(" + (int) PMath.intoLevel(d.xp, Prog.levelsFile.xpPerLevel) + "/" + Prog.levelsFile.xpPerLevel + ")");
        l.add("&fFairy Souls: &d" + Prog.fairy(p).found.size() + "&7/&d" + Prog.fairy.total());
        l.add("&fBestiary: &3Milestone " + Prog.bestiary.milestone(p));
        return l;
    }

    // ---- commands ------------------------------------------------------------------------------------------

    private void registerCommands() {
        Sky.commands().register(SkyCommand.of("sblevel", "Open SkyBlock Leveling (your level, tasks, rewards, emblems)")
                .alias("leveling", "skyblocklevel", "sbxp").category("Progression").playerOnly()
                .run((s, a) -> {
                    if (a.length > 0 && (a[0].equalsIgnoreCase("guide") || a[0].equalsIgnoreCase("next"))) Prog.levels.sendGuide((Player) s);
                    else Prog.levels.openMenu((Player) s);
                }));
        Sky.commands().register(SkyCommand.of("xpguide", "The quickest SkyBlock XP for you right now (nearest skill levels, collection tiers, souls...)")
                .alias("sbxpguide", "nextxp").category("Progression").playerOnly()
                .run((s, a) -> Prog.levels.sendGuide((Player) s)));
        Sky.commands().register(SkyCommand.of("questlog", "Open the Quest Log (your objectives)")
                .alias("quests", "objectives", "objective").category("Progression").playerOnly()
                .run((s, a) -> Prog.objectives.openMenu((Player) s)));
        Sky.commands().register(SkyCommand.of("fairysouls", "See your Fairy Souls (exchange them at the fairy in the Hub)")
                .alias("souls", "fairy").category("Progression").playerOnly()
                .run((s, a) -> Prog.fairy.openMenu((Player) s, false)));
        Sky.commands().register(SkyCommand.of("bestiary", "Open the Bestiary").category("Progression").playerOnly()
                .run((s, a) -> Prog.bestiary.openMenu((Player) s)));
        Sky.commands().register(SkyCommand.of("museum", "Open the Museum (donate items for SkyBlock XP)").category("Progression").playerOnly()
                .run((s, a) -> Prog.museum.openMenu((Player) s)));
        Sky.commands().register(SkyCommand.of("islandguides", "Open the Island Guides (first-visit objectives of every island)")
                .alias("islandguide", "iguides").category("Progression").playerOnly()
                .run((s, a) -> Prog.islandGuides.openMenu((Player) s)));
        Sky.commands().register(SkyCommand.of("cakes", "See your Century Cake buffs and claim celebration cakes")
                .alias("cake", "centurycakes").category("Progression").playerOnly()
                .run((s, a) -> Prog.cakes.openMenu((Player) s)));
    }
}
