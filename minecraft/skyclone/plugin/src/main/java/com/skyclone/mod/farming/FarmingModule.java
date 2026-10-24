package com.skyclone.mod.farming;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.IslandDef;
import com.skyclone.mod.farming.gen.BarnGenerator;
import com.skyclone.mod.farming.gen.DesertGenerator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.function.Consumer;

/**
 * Farming: crop breaking with fortune and regrowth on the hub fields, The Barn and the Mushroom Desert; every
 * hoe and farming tool (counters, levels, almanacs, dicers); the Harvest Fair (hourly farming contests with
 * medals, tickets and NPC competitors); Harvesta's medal exchange (Extra Fortune, Farming level cap); the
 * desert trapper and his pelt shops; and the two farming islands themselves.
 */
public final class FarmingModule implements GameModule, Listener {

    public static final String KEY = "farming";
    static final String HOST_NAME = "&6Harvest Warden Ambrose";
    static final String ANITA_NAME = "&dHarvesta";
    static final String TRAPPER_NAME = "&aTavish";

    static FarmingModule M;

    FDials dials;
    Tables tables;
    Regrowth regrowth;
    ToolTracker tools;
    Harvest harvest;
    Contests contests;
    Trapper trapper;
    Treasure treasure;
    Shepherd shepherd;
    FarmMenus menus;
    Shops shops;
    Perks perks;
    Shiny shiny;
    FarmListener listener;

    @Override public String id() { return "farming"; }

    @Override
    public void enable() {
        M = this;
        dials = new FDials();
        tables = Tables.fromJar(getClass().getClassLoader());

        Sky.items().loadJson("farming", "items.json");
        Sky.recipes().loadJson("farming", "recipes.json");
        Sky.mobs().loadJson("farming", "mobs.json");
        Sky.mobs().loadSpawnRules("farming", "spawns.json");

        // ---- islands + warps -------------------------------------------------------------------------
        IslandDef barn = IslandDef.of("barn", "The Barn", new BarnGenerator());
        barn.requirement = blankToNull(dials.barnRequirement.get());
        Sky.worlds().registerIsland(barn);
        IslandDef desert = IslandDef.of("mushroom_desert", "Mushroom Desert", new DesertGenerator());
        desert.requirement = blankToNull(dials.desertRequirement.get());
        Sky.worlds().registerIsland(desert);
        Sky.worlds().registerWarp("barn", "barn", "spawn", "The Barn", blankToNull(dials.barnRequirement.get()));
        Sky.worlds().registerWarp("desert", "mushroom_desert", "spawn", "Mushroom Desert", blankToNull(dials.desertRequirement.get()));
        Sky.worlds().registerWarp("trapper", "mushroom_desert", "warp.trapper", "Trapper's Den", blankToNull(dials.desertRequirement.get()));

        // ---- systems ---------------------------------------------------------------------------------
        perks = new Perks();
        perks.load(getClass().getClassLoader());
        regrowth = new Regrowth();
        regrowth.start();
        tools = new ToolTracker();
        tools.start();
        harvest = new Harvest(this);
        Sky.worlds().addBreakHandler(harvest);
        contests = new Contests(this);
        contests.start();
        trapper = new Trapper(this);
        trapper.start();
        treasure = new Treasure(this);
        treasure.start();
        Sky.worlds().addBreakHandler(treasure);
        shepherd = new Shepherd(this);
        shepherd.start();
        Bukkit.getPluginManager().registerEvents(shepherd, Sky.plugin());
        shiny = new Shiny(this);
        shiny.start();
        Bukkit.getPluginManager().registerEvents(shiny, Sky.plugin());
        menus = new FarmMenus(this);
        shops = new Shops(this);
        listener = new FarmListener(this);

        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(tools, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(contests, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(trapper, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(listener, Sky.plugin());

        Sky.stats().addProvider("farming", (p, map) -> {
            if (!Sky.profiles().isReady(p)) return;
            FarmData d = data(p);
            if (d.anitaFortune > 0) map.add(Stat.FARMING_FORTUNE, d.anitaFortune * (double) dials.anitaPerTier.get());
            if (listener.swiftSlicer(p)) map.add(Stat.SPEED, dials.swiftSlicerSpeed.get());
        });

        // The contest goes before the trapper: it is the time-critical one (the trapper also lives in /trapper).
        Sky.sidebar().section("farming_contest", 250, p -> contests.sidebar(p));
        Sky.sidebar().section("farming_trapper", 300, p -> trapper.sidebar(p));

        // "harvest_fair" (garden request 8b, the Redline chip): true while a fair runs; with a crop, only for its three
        Sky.requirements().register("harvest_fair", new com.skyclone.api.util.Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (contests == null || !contests.active) return false;
                if (parts.length < 2 || parts[1].isBlank()) return true;
                Crop c = Crop.parse(parts[1]);
                return c != null && contests.crops.contains(c);
            }
            @Override public String describe(String[] parts) {
                Crop c = parts.length > 1 ? Crop.parse(parts[1]) : null;
                return "&eA Harvest Fair" + (c != null ? " for " + c.display : "") + " running";
            }
        });

        registerNpcs();
        registerCommands();
        FarmingAdmin.register(this);
        FarmingSelfTests.register(this);
    }

    @Override
    public void postEnable() {
        // The skills module starts Farming's cap at 50; re-apply bought steps for whoever is online (reloads).
        for (Player p : Bukkit.getOnlinePlayers()) if (Sky.profiles().isReady(p)) applyCap(p);
        perks.reindex();                                   // every module's accessories are registered now
        gardenRequirements();
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::farmerOrbs, 20L, 20L);
    }

    /** Tools that got a garden:N requirement at boot (admin info). */
    int gardenGated;

    /**
     * The reference gates every crop tool on a Garden level too (items.json GARDEN_LEVEL, kept in the tool spec as
     * {@code gardenLevel}). Added only when the garden module registered its requirement key - an unknown prefix fails
     * closed and would lock every tool on a server without the garden.
     */
    void gardenRequirements() {
        if (!dials.toolGardenRequirement.get() || !Sky.requirements().known("garden")) return;
        int n = 0;
        for (com.skyclone.api.item.ItemDef d : Sky.items().all()) {
            ToolSpec s = d == null ? null : ToolSpec.of(d.id, d.extra);
            if (s == null || s.gardenLevel <= 0) continue;
            String key = "garden:" + s.gardenLevel;
            try {
                if (d.requirements == null) d.requirements = new java.util.ArrayList<>();
                if (!d.requirements.contains(key)) { d.requirements.add(key); n++; }
            } catch (UnsupportedOperationException e) {
                java.util.List<String> copy = new java.util.ArrayList<>(d.requirements);
                copy.add(key);
                d.requirements = copy;
                n++;
            }
        }
        gardenGated = n;
        if (n > 0) Sky.log().info("[farming] " + n + " crop tools now also need their Garden level (dial farming.tools.garden-requirement)");
    }

    private int orbTick;

    /**
     * Farmer Orb (accessory, research 06: "regrowing an extra crop every 3 seconds" near you on the public islands):
     * every {@code farming.farmer-orb.seconds} the regrowing crop nearest to finishing within the radius finishes now.
     */
    private void farmerOrbs() {
        int every = Math.max(1, dials.orbSeconds.get());
        if (++orbTick % every != 0 || regrowth.pendingCount() == 0) return;
        double r = Math.max(1, dials.orbRadius.get());
        int n = Math.max(0, dials.orbCrops.get());
        if (n == 0) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            String island = Sky.worlds().islandOf(p.getWorld());
            if (island == null || !dials.islandSet().contains(island)) continue;
            if (!Perks.has(p, "FARMER_ORB")) continue;
            regrowth.hasten(p.getLocation(), r, n);
        }
    }

    @Override
    public void disable() {
        if (tools != null) tools.flushAll();
        if (regrowth != null) regrowth.stop();
        if (contests != null) contests.stop();
        if (shiny != null) shiny.stop();
    }

    static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    FarmData data(Player p) { return Sky.profiles().member(p, KEY, FarmData.class); }

    /** Farming level cap = 50 + bought steps (never lowered by us). */
    void applyCap(Player p) {
        FarmData d = data(p);
        if (d.capSteps <= 0 || !Sky.has(SkillService.class)) return;
        SkillService s = Sky.service(SkillService.class);
        int want = Math.min(SkillType.FARMING.maxLevel, 50 + d.capSteps);
        if (s.cap(p, SkillType.FARMING) < want) s.setCap(p, SkillType.FARMING, want);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline() && Sky.profiles().isReady(p)) applyCap(p); }, 5L);
    }

    // =================================================================================================
    // NPCs
    // =================================================================================================

    private void npc(String id, String name, String title, String island, String anchor, String skinKey, Consumer<Player> click) {
        NpcDef d = NpcDef.of(id, name, island, anchor).title(title);
        String skin = tables.npcSkins.get(skinKey);
        if (skin != null) d.skin(skin, null);
        Sky.npcs().register(d, click);
    }

    private void registerNpcs() {
        Consumer<Player> host = p -> {
            FarmData d = data(p);
            if (!d.metHost) {
                d.metHost = true;
                Sky.npcs().talk(p, "farming_host", List.of(
                        "&fWelcome, friend! Every hour the island holds a &6Harvest Fair&f.",
                        "&fThree crops, twenty minutes. Farm here, at the Barn, in the Desert or in your Garden - I'll tally it.",
                        "&fPlace well and you'll earn &6medals &fand &aHarvest Fair Tickets&f - Harvesta trades them for upgrades.",
                        "&fYou're signed up. Reach &aFarming X &fand the next fair is yours to win."),
                        () -> menus.openContest(p));
            } else menus.openContest(p);
        };
        npc("farming_host", HOST_NAME, "&e&lHARVEST FAIR", "hub", "npc.contest_host", "contest_host", host);
        npc("farming_host_barn", HOST_NAME, "&e&lHARVEST FAIR", "barn", "npc.contest_host", "contest_host", host);
        npc("farming_harvesta", ANITA_NAME, "&e&lMEDAL EXCHANGE", "barn", "npc.harvesta", "anita", p -> shops.openAnita(p));
        npc("farming_farmhand", "&fFarmhand Brody", "&e&lTOOLS", "barn", "npc.farmhand", "farmhand", p -> shops.openFarmhand(p));
        npc("farming_guide", "&fOld Farmer Tewksbury", "&e&lFARMING GUIDE", "barn", "npc.farm_guide", "farmer",
                p -> Sky.npcs().talk(p, "farming_guide", List.of(
                        "&fEvery crop you break here grows back on its own in a few seconds.",
                        "&fFarming Fortune is everything: each &6100☘ &fis one more guaranteed drop.",
                        "&fCrop tools add fortune for their own crop, and the good ones keep a counter."), () -> menus.openMain(p)));
        npc("farming_trapper", TRAPPER_NAME, "&e&lTRAPPER", "mushroom_desert", "npc.trapper", "trapper", p -> trapper.talk(p));
        npc("farming_surveyor", "&fSurveyor Pruitt", "&e&lPELT SHOP", "mushroom_desert", "npc.surveyor", "talbot",
                p -> shops.openPeltShop(p, "talbot", "Surveyor Pruitt"));
        npc("farming_fur_trader", "&fFur-trader Alder", "&e&lMINION UPGRADES", "mushroom_desert", "npc.fur_trader", "tony",
                p -> shops.openPeltShop(p, "tony", "Fur-trader Alder"));
        npc("farming_shepherd", "&fShepherd Maela", "&e&lSHEARING TASK", "mushroom_desert", "npc.shepherd", "shepherd",
                p -> shepherd.talk(p));
        npc("farming_treasure_hunter", "&fDigger Nim", "&e&lTREASURE HINTS", "mushroom_desert", "npc.treasure_hunter", "treasure_hunter",
                p -> treasure.talk(p));
    }

    // =================================================================================================
    // Player commands
    // =================================================================================================

    private void registerCommands() {
        Sky.commands().register(SkyCommand.of("farming", "Open the Farming menu (fortune, crops, tools, contests)")
                .alias("farm").category("Skills").playerOnly().run((s, a) -> menus.openMain((Player) s)));
        Sky.commands().register(SkyCommand.of("harvestfair", "Harvest Fair: current contest, schedule, medals, rewards")
                .alias("jacob", "contest", "contests").category("Skills").playerOnly().run((s, a) -> menus.openContest((Player) s)));
        Sky.commands().register(SkyCommand.of("medals", "Harvesta's Medal Exchange (contest medals and tickets)")
                .alias("anita", "harvesta").category("Skills").playerOnly().run((s, a) -> shops.openAnita((Player) s)));
        Sky.commands().register(SkyCommand.of("farmtool", "Upgrade the farming tool in your hand")
                .alias("hoe", "tool").category("Skills").playerOnly().run((s, a) -> menus.openTool((Player) s)));
        Sky.commands().register(SkyCommand.of("trapper", "Your trapper quest and pelts")
                .alias("pelts").category("Skills").playerOnly().run((s, a) -> trapper.openQuest((Player) s)));
        Sky.commands().register(SkyCommand.of("treasurehunter", "Digger Nim's buried-treasure hints (Mushroom Desert)")
                .alias("dig", "nim").category("Skills").playerOnly().run((s, a) -> treasure.open((Player) s)));
        Sky.commands().register(SkyCommand.of("shinypigs", "Shiny Pigs: catch them while farming, bring them to their orb")
                .alias("shinypig").category("Skills").playerOnly().run((s, a) -> shiny.open((Player) s)));
        Sky.commands().register(SkyCommand.of("fortune", "Your fortune for every crop, by source")
                .alias("ff").category("Skills").playerOnly().run((s, a) -> {
                    Player p = (Player) s;
                    Crop c = a.length > 0 ? Crop.parse(String.join("_", a)) : null;
                    if (c != null) menus.openCrop(p, c); else menus.openCrops(p);
                }).tab((s, a) -> a.length == 1 ? java.util.Arrays.stream(Crop.values()).map(c -> c.name().toLowerCase()).toList() : List.of()));
    }

    /** Sends a player to a warp through core (requirement checked there). */
    static void warp(Player p, String warp) {
        if (!Sky.worlds().warp(p, warp)) Msg.sound(p, org.bukkit.Sound.ENTITY_VILLAGER_NO, 1f);
    }

    static boolean has(Class<?> c) { return Sky.has(c); }

    static Features.SkyMenu skyMenu() { return Sky.service(Features.SkyMenu.class); }
}
