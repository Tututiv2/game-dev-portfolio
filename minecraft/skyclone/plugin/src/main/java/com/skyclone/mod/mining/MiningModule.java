package com.skyclone.mod.mining;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.util.Requirements;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Mining: custom block breaking with Mining Speed / Block Strength / Breaking Power, fortune, pristine
 * and spread; ore regeneration; powders and the Heart of the Mountain; pickaxe abilities; commissions;
 * the Forge; drills; gemstones and the Crystal Nucleus; the frozen tunnels with Cold, mineshafts,
 * corpses and fossils; mining events and the Deep Market. Research 07 section 2.
 */
public final class MiningModule implements GameModule, Listener {

    MiningDials dials;
    BlockTable blocks;
    Hotm hotm;
    Regen regen;
    TempBlocks temp;
    Drills drills;
    SkyMall skyMall;
    MiningEvents events;
    Feedback feedback;
    Commissions comms;
    Forge forge;
    PickaxeAbilities abilities;
    Deployables deploy;
    MiningStats stats;
    Finds finds;
    Hollows hollows;
    Tunnels tunnels;
    Excavator excavator;
    MiningLoot loot;
    MiningMenus menus;
    HotmMenu hotmMenu;
    DigEngine engine;
    Breaker breaker;
    IslandMining islandMining;
    IslandSpeed islandSpeed;
    Consumables consumables;

    @Override public String id() { return "mining"; }

    static Reader resource(String file) {
        InputStream in = Sky.plugin().getResource("data/mining/" + file);
        if (in == null) throw new IllegalStateException("missing data/mining/" + file);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    @Override
    public void enable() {
        dials = new MiningDials();
        Sky.items().loadJson("mining", "items.json");
        Sky.mobs().loadJson("mining", "mobs.json");
        Sky.recipes().loadJson("mining", "recipes.json");
        try (Reader b = resource("blocks.json"); Reader h = resource("hotm.json"); Reader l = resource("loot.json");
             Reader c = resource("commissions.json"); Reader f = resource("forge.json")) {
            blocks = BlockTable.load(b);
            HotmTree tree = HotmTree.load(h);
            hotm = new Hotm(this, tree);
            loot = new MiningLoot(this, l);
            comms = new Commissions(this, c);
            forge = new Forge(this, f);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("mining data", e);
        }
        regen = new Regen();
        temp = new TempBlocks();
        drills = new Drills(this);
        skyMall = new SkyMall(this);
        events = new MiningEvents(this);
        feedback = new Feedback(this);
        abilities = new PickaxeAbilities(this);
        deploy = new Deployables(this);
        stats = new MiningStats(this);
        finds = new Finds(this);
        hollows = new Hollows(this);
        tunnels = new Tunnels(this);
        excavator = new Excavator(this);
        menus = new MiningMenus(this);
        hotmMenu = new HotmMenu(this);
        breaker = new Breaker(this);
        engine = new DigEngine(this);
        consumables = new Consumables(this);
        islandMining = new IslandMining(this);
        islandSpeed = new IslandSpeed(this);

        var pm = Bukkit.getPluginManager();
        for (Listener l : List.of(this, engine, finds, comms, events, tunnels, consumables, islandMining, islandSpeed)) pm.registerEvents(l, Sky.plugin());

        Sky.provide(Features.Mining.class, hotm);
        Sky.requirements().register("hotm", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 2 || !MiningUtil.ready(p)) return false;
                try { return hotm.tier(p) >= Integer.parseInt(parts[1].trim()); } catch (NumberFormatException e) { return false; }
            }
            @Override public String describe(String[] parts) { return "&5Heart of the Mountain Tier " + (parts.length > 1 ? parts[1] : "?"); }
        });

        registerNpcs();
        Sky.sidebar().section("mining", dials.sidebarPriority.get(), p -> menus.sidebar(p));
        registerTabWidgets(dials.tabPriority.get());
        dials.tabPriority.onChange(this::registerTabWidgets);   // tabWidget() replaces by id, so a dial change is live
        MiningCommands.register(this);
        MiningSelfTests.register(this);
    }

    /** Commissions + Powders tab widgets at {@code priority} / +1 (core guide: module widgets use >= 100). */
    private void registerTabWidgets(Integer priority) {
        int pr = Math.max(100, priority == null ? 105 : priority);
        Sky.sidebar().tabWidget("mining_commissions", pr, p -> menus.tabCommissions(p));
        Sky.sidebar().tabWidget("mining_powders", pr + 1, p -> menus.tabPowders(p));
    }

    @Override
    public void postEnable() {
        forge.scheduleRegistration();
        registerMuncherBody();
    }

    private boolean muncherBody;

    /**
     * The Fossil Muncher's body, only once the hollows module's Base Camp has an anchor {@code npc.fossil_muncher}
     * (requested; a missing anchor would park the NPC at spawn and fail core's npc-anchor self-test). Checked after
     * boot and each time the frozen tunnels are (re)built, so the body appears the day the anchor does.
     */
    void registerMuncherBody() {
        if (muncherBody) return;
        try {
            if (Sky.worlds().anchor("glacite_tunnels", "npc.fossil_muncher") == null) return;
        } catch (RuntimeException ex) { return; }
        muncherBody = true;
        Sky.npcs().register(NpcDef.of("mining_fossil_muncher", "&9Fossil Muncher", "glacite_tunnels", "npc.fossil_muncher").title("&e&lDAILY FOSSIL"),
                p -> {
                    if (MiningUtil.today().equals(MiningUtil.data(p).muncherDate)) { excavator.openMuncher(p); return; }
                    Sky.npcs().talk(p, "mining_fossil_muncher", List.of("&fBones. Old bones. Feed me one a day.",
                            "&fI pay in powder and essence, and I never forget a meal."), () -> excavator.openMuncher(p));
                });
    }

    boolean muncherBodyRegistered() { return muncherBody; }

    @EventHandler
    public void onBuilt(SkyEvents.IslandBuilt e) {
        if ("glacite_tunnels".equals(e.islandId)) registerMuncherBody();
    }

    @Override
    public void disable() {
        try { tunnels.shutdown(); } catch (Throwable t) { Sky.log().warning("[mining] tunnels shutdown: " + t); }
        try { events.shutdown(); } catch (Throwable t) { Sky.log().warning("[mining] events shutdown: " + t); }
        try { finds.clear(); } catch (Throwable ignored) { }
        try { deploy.clear(); } catch (Throwable ignored) { }
        try { engine.releaseAll(); } catch (Throwable ignored) { }
        try { islandSpeed.releaseAll(); } catch (Throwable ignored) { }
        try { regen.restoreAll(); } catch (Throwable t) { Sky.log().warning("[mining] regen restore: " + t); }
    }

    private void registerNpcs() {
        Sky.npcs().register(NpcDef.of("mining_king", "&6King of the Mines", "dwarven_mines", "npc.mine_king").title("&e&lDAILY GIFT"),
                p -> Sky.npcs().talk(p, "mining_king", List.of("&fWelcome to my mines, miner.", "&fThe throne changes hands each day; today it is mine."),
                        () -> menus.openKing(p)));
        Sky.npcs().register(NpcDef.of("mining_commissions", "&6Emissary Torvald", "dwarven_mines", "npc.commissions").title("&e&lCOMMISSIONS"),
                p -> comms.open(p, null));
        Sky.npcs().register(NpcDef.of("mining_forge", "&6Forgemaster Kettrick", "dwarven_mines", "npc.forge").title("&e&lTHE FORGE"),
                p -> forge.open(p));
        Sky.npcs().register(NpcDef.of("mining_hotm", "&5Spirit of the Mountain", "dwarven_mines", "npc.hotm").title("&d&lHEART OF THE MOUNTAIN"),
                p -> {
                    MiningData d = MiningUtil.data(p);
                    if (!d.hotmIntroSeen) {
                        d.hotmIntroSeen = true;
                        Sky.npcs().talk(p, "mining_hotm", List.of("&fThe mountain has a heart, and it beats for those who dig.",
                                "&fFinish commissions to wake it. Spend your powder here.", "&fRight-click a pickaxe or drill to use the ability you pick."),
                                () -> hotmMenu.open(p, 0));
                    } else hotmMenu.open(p, 0);
                });
        // the Dwarven village plaza regulars (anchors by the mines module, wave 3); before wave 5 these were menus only
        Sky.npcs().register(NpcDef.of("mining_fetcher", "&eFetcher", "dwarven_mines", "npc.fetcher").title("&e&lDAILY REQUEST"),
                p -> {
                    MiningData d = MiningUtil.data(p);
                    MiningUtil.rollDay(d);
                    if (MiningUtil.today().equals(d.fetchurDate)) { menus.openFetcher(p); return; }
                    Sky.npcs().talk(p, "mining_fetcher", List.of("&fYou! Yes, you. I need something and I need it today.",
                            "&fBring it and the mountain pays you back."), () -> menus.openFetcher(p));
                });
        Sky.npcs().register(NpcDef.of("mining_riddlewright", "&dRiddlewright", "dwarven_mines", "npc.riddlewright").title("&d&lDAILY PUZZLE"),
                p -> {
                    MiningData d = MiningUtil.data(p);
                    if (d.puzzlerDate != null && d.puzzlerDate.endsWith(MiningUtil.today())) { menus.openPuzzle(p); return; }   // solved or failed today
                    Sky.npcs().talk(p, "mining_riddlewright", List.of("&fFollow the arrows and tell me where they end.",
                            "&fOne answer a day. Guess wrong and you wait for tomorrow."), () -> menus.openPuzzle(p));
                });
        Sky.npcs().register(NpcDef.of("mining_drill_mechanic", "&6Drill Mechanic", "dwarven_mines", "npc.drill_mechanic").title("&6&lDRILLS"),
                p -> drills.openMechanic(p));
        NpcDef merchant = NpcDef.of("mining_hollows_merchant", "&dHollows Merchant Ondine", "crystal_hollows", "npc.hollows_merchant").title("&e&lSHOP & NUCLEUS");
        Sky.npcs().register(merchant, p -> menus.openMerchant(p));
        // the frozen tunnels' Base Camp (anchors built by the hollows module's TunnelsGenerator)
        Sky.npcs().register(NpcDef.of("mining_camp_commissions", "&bEmissary Frosta", "glacite_tunnels", "npc.camp_commissions").title("&e&lCOMMISSIONS"),
                p -> comms.open(p, "glacite_tunnels"));
        Sky.npcs().register(NpcDef.of("mining_camp_excavator", "&6Fossil Digger Ambleside", "glacite_tunnels", "npc.camp_excavator").title("&e&lFOSSIL EXCAVATOR"),
                p -> excavator.open(p));
        Sky.npcs().register(NpcDef.of("mining_camp_quartermaster", "&bQuartermaster Hild", "glacite_tunnels", "npc.camp_quartermaster").title("&e&lBASE CAMP"),
                p -> Sky.npcs().talk(p, "mining_camp_quartermaster", List.of("&fCold out there. Warm up by the fire before you head down.",
                        "&fMine long enough and the floor opens into a frozen mineshaft."), () -> menus.openCamp(p)));
    }

    private MiningData d(Player p) { return MiningUtil.data(p); }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        String island = MiningUtil.island(p);
        if (island != null && MiningUtil.POWDER_ISLANDS.contains(island)) hotm.awaken(p);   // logged out down there
        hotm.payTierRewards(p);   // no-op until the Heart is awake, so a fresh profile is paid nothing
        comms.milestones(p);
        MiningUtil.rollDay(d(p));
    }

    /** First arrival in the Dwarven Mines (or any powder island) wakes the Heart and pays Tier 1. */
    @EventHandler
    public void onIsland(SkyEvents.IslandChange e) {
        if (e.to != null && MiningUtil.POWDER_ISLANDS.contains(e.to)) hotm.awaken(e.player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        tunnels.quit(p);
        abilities.forget(p.getUniqueId());
        hollows.forget(p.getUniqueId());
        finds.forget(p.getUniqueId());
        feedback.forget(p.getUniqueId());
        excavator.quit(p.getUniqueId());
    }
}
