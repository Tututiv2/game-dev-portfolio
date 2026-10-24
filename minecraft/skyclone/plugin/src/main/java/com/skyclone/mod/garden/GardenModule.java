package com.skyclone.mod.garden;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.world.IslandDef;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

/**
 * The Garden (research 08 section 1): a per-profile farming island of 24 plots around a barn,
 * Garden levels, crop milestones and upgrades, visitors and Copper, the composter, pests and
 * vacuums, the greenhouse, the Copper Market, barn skins, garden chips and plot presets.
 */
public final class GardenModule implements GameModule, Listener {

    static Growth growth;
    static Pests pests;
    static Visitors visitors;

    /** NPCs that live in every garden instance (placed per instance with spawnAt). */
    static final String[][] INSTANCE_NPCS = {
            {"garden_market", "npc.market"}, {"garden_pestwarden", "npc.pestwarden"},
            {"garden_botanist", "npc.botanist"}, {"garden_guide", "npc.barn_hall"}};

    @Override public String id() { return "garden"; }

    @Override
    public void enable() {
        G.dials();
        G.T = G.load();
        Sky.items().loadJson("garden", "items.json");
        Sky.mobs().loadJson("garden", "mobs.json");
        Sky.recipes().loadJson("garden", "recipes.json");

        IslandDef def = IslandDef.of(G.ISLAND, "The Garden", new GardenGenerator());
        def.instanced = true;
        def.instanceSpacing = 1024;
        Sky.worlds().registerIsland(def);
        registerWarp(G.requirement.get());
        G.requirement.onChange(GardenModule::registerWarp);     // /sb dial set garden.requirement takes effect at once

        growth = new Growth();
        growth.start();
        pests = new Pests();
        pests.register();
        visitors = new Visitors();
        visitors.registerNpcs();
        npcs();

        Bukkit.getPluginManager().registerEvents(new CropListener(growth), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(pests, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());

        Sky.provide(Features.Garden.class, new Features.Garden() {
            @Override public int gardenLevel(Player p) { return G.ready(p) ? G.level(G.data(p)) : 0; }
            @Override public long copper(Player p) { return G.ready(p) ? G.data(p).copper : 0; }
        });
        Sky.requirements().register("garden", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (!G.ready(p) || parts.length < 2) return false;
                GardenData d = G.data(p);
                try {
                    if (parts[1].equals("unique")) return d.uniqueServed() >= Integer.parseInt(parts[2]);
                    if (parts[1].equals("plots")) return d.plots.size() >= Integer.parseInt(parts[2]);
                    // garden:chip:<CHIP_ID>:<n> - a chip redeemed to level n (the farming module reads the Cogmind chip this way)
                    if (parts[1].equals("chip")) return Chips.level(d, parts[2].toUpperCase(java.util.Locale.ROOT)) >= Integer.parseInt(parts[3]);
                    // garden:analyzer:<n> - Crop Analyzer milestone n (crafting's recipe unlocks; wave-5 integration)
                    if (parts[1].equals("analyzer")) return Greenhouse.analyzerLevel(d) >= Integer.parseInt(parts[2]);
                    return G.level(d) >= Integer.parseInt(parts[1]);
                } catch (Exception e) { return false; }
            }

            @Override public String describe(String[] parts) {
                if (parts.length >= 3 && parts[1].equals("unique")) return "&a" + parts[2] + " Unique Visitors";
                if (parts.length >= 3 && parts[1].equals("plots")) return "&a" + parts[2] + " Garden Plots";
                if (parts.length >= 3 && parts[1].equals("analyzer")) {
                    try { return "&aCrop Analyzer Milestone " + GardenMath.roman(Integer.parseInt(parts[2])); } catch (Exception e) { return "&aCrop Analyzer Milestone " + parts[2]; }
                }
                if (parts.length >= 4 && parts[1].equals("chip")) return "&a" + G.itemName(parts[2].toUpperCase(java.util.Locale.ROOT)) + " " + parts[3];
                try { return "&aGarden Level " + GardenMath.roman(Integer.parseInt(parts[1])); } catch (Exception e) { return "&aGarden Level " + (parts.length > 1 ? parts[1] : "?"); }
            }
        });
        Sky.stats().addProvider("garden", (p, map) -> {
            if (!G.ready(p)) return;
            GardenData d = G.data(p);
            map.add(Stat.FARMING_FORTUNE, d.plots.size() * G.plotFortune.get()
                    + Greenhouse.analyzerLevel(d) * G.T.greenhouse.analyzerReward.fortune + G.chipValue(d, "ff"));
            // Green Bandana on the summoned pet: +4 Farming Fortune per Garden level (lane 12; NEU lore)
            map.add(Stat.FARMING_FORTUNE, Effects.greenBandanaFortune(p, G.level(d)));
            map.add(Stat.FARMING_WISDOM, G.chipValue(d, "wisdom"));
            // wave 5 chips: Vermin Vaporizer (+3 Bonus Pest Chance a level), Hypercharge (temporary Farming Fortune buffs +3% a level)
            map.add(Stat.PEST_CHANCE, G.chipValue(d, "pest_chance"));
            // Pestwarden's Bonus (turned in at the Pestwarden): Farming Fortune while it runs
            if (G.onGarden(p)) map.add(Stat.FARMING_FORTUNE, Effects.pesthunterFortune(G.member(p)));
            double hyper = G.chipValue(d, "ff_buffs");
            if (hyper > 0) map.add(Stat.FARMING_FORTUNE, EffectMath.hypercharge(Effects.temporaryFortune(p), hyper));
            GardenMember m = G.member(p);
            // wave 5: Peony / Blossom piece bonus (grown by visitor offers) and the Rose Dragon's Rosy Scales
            map.add(Stat.FARMING_FORTUNE, Effects.pieceFortune(p, m));
            int tiers = 0;
            for (int t : d.milestonePaid.values()) tiers += Math.max(0, t);
            map.add(Stat.FARMING_FORTUNE, EffectMath.perMilestone(Effects.pet(p, "rose_dragon_rosy_scales", "a"), tiers));
            map.add(Stat.SPEED, EffectMath.perMilestone(Effects.pet(p, "rose_dragon_rosy_scales", "b"), tiers));
            double bpc = m.larva * 2;
            if (G.onGarden(p)) {
                int[] t = G.template(G.key(p), p.getLocation());
                String plot = t == null ? null : G.plotAt(t[0], t[2]);
                GardenData.PlotState s = plot == null ? null : d.plots.get(plot);
                if (s != null && s.sprayUntil > System.currentTimeMillis()) bpc += s.sprayBpc;
            }
            map.add(Stat.PEST_CHANCE, bpc);
        });
        Sky.sidebar().section("garden", 300, this::sidebar);
        Sky.sidebar().tabWidget("garden", 140, this::tab);
        Sky.items().addLoreContributor("garden_tools", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }

            @Override public List<String> lines(ItemDef def, ItemData data) {
                if (def == null) return List.of();
                if (def.has("watering_can")) {
                    int cap = Greenhouse.capacity(def);
                    int w = data != null && data.extra.has("water") ? data.extra.get("water").getAsInt() : cap;
                    return List.of("&7Water: &b" + Text.commasInt(w) + "&7/&b" + Text.commasInt(cap));
                }
                if (def.category == com.skyclone.api.item.ItemCategory.VACUUM) {
                    long bag = data != null && data.extra.has("vacuum_bag") ? data.extra.get("vacuum_bag").getAsLong() : 0;
                    return List.of("&7Pests vacuumed with it: &6" + Text.commasInt(bag), "&8Your bag of Pests is in /pests.");
                }
                if (G.T.sprayer(def.id) != null) {
                    String sel = data != null && data.extra.has("spray") ? data.extra.get("spray").getAsString() : "COMPOST";
                    return List.of("&7Selected Material: " + G.itemName(sel));
                }
                return List.of();
            }
        });
        // Peony / Blossom equipment: the piece bonus grown by visitor offers, drawn on the item
        Sky.items().addLoreContributor("garden_pieces", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(ItemDef def, ItemData data) { return Effects.pieceLore(def, data); }
        });
        GardenCommands.register();
        GardenTests.register();
    }

    @Override
    public void postEnable() {
        // NPC bodies for anyone already online after a reload
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            for (Player p : Bukkit.getOnlinePlayers()) if (G.ready(p) && G.onGarden(p) && G.inOwnGarden(p)) enterGarden(p);
        }, 40L);
    }

    private static void registerWarp(String req) {
        Sky.worlds().registerWarp("garden", G.ISLAND, "spawn", "The Garden", req == null || req.isBlank() ? null : req);
    }

    @Override
    public void disable() {
        // the regrowth queue lives in memory: grow everything still pending so no harvested spot is lost
        if (growth != null) {
            int n = growth.finishAll();
            if (n > 0) Sky.log().info("[garden] grew " + n + " pending crops back before shutdown");
        }
    }

    // =====================================================================================
    // NPCs
    // =====================================================================================

    private void npcs() {
        NpcDef gardener = NpcDef.of("garden_gardener", "&aGardener Rowena", "hub", "npc.gardener").title("&e&lTHE GARDEN");
        gardener.entity = "VILLAGER";
        Sky.npcs().register(gardener, p -> {
            boolean met = G.ready(p) && G.member(p).metGardener;
            List<String> lines = met ? List.of("&fOff to your Garden? Mind the pests!")
                    : List.of("&fEvery islander deserves a proper Garden.", "&fYours is waiting, plots and all. I keep the gate.",
                    "&fGrow, compost, buy more land, and the visitors will come.");
            Sky.npcs().talk(p, "garden_gardener", lines, () -> {
                if (G.ready(p)) G.member(p).metGardener = true;
                Sky.worlds().warp(p, "garden");
            });
        });
        NpcDef market = NpcDef.of("garden_market", "&aMarten the Grocer", G.ISLAND, "npc.market").title("&6&lCOPPER MARKET");
        Sky.npcs().register(market, p -> { if (G.ready(p)) Market.open(p, "All", 0); });
        NpcDef warden = NpcDef.of("garden_pestwarden", "&2Pestwarden Pell", G.ISLAND, "npc.pestwarden").title("&c&lPESTS");
        Sky.npcs().register(warden, p -> { if (G.ready(p)) PestMenus.pestwarden(p).open(p); });
        NpcDef botanist = NpcDef.of("garden_botanist", "&bBotanist Fern", G.ISLAND, "npc.botanist").title("&b&lGREENHOUSE");
        Sky.npcs().register(botanist, p -> { if (G.ready(p)) Greenhouse.open(p); });
        NpcDef guide = NpcDef.of("garden_guide", "&eFarmhand Wynn", G.ISLAND, "npc.barn_hall").title("&e&lGUIDE");
        guide.entity = "VILLAGER";
        Sky.npcs().register(guide, p -> Sky.npcs().talk(p, "garden_guide", List.of(
                "&fThe lectern there is your &aDesk&f: plots, upgrades, the lot.",
                "&fFeed crops and fuel to the &acomposter&f; compost buys new plots.",
                "&fVisitors pay &cCopper&f for crops. Spend it at Marten's stall.",
                "&fFrom Garden V, &cpests&f turn up while you farm. Vacuum them!"), () -> { if (G.ready(p)) Desk.open(p); }));
    }

    /** A member arrived in their own garden: first-visit setup, NPC copies, visitors. */
    static void enterGarden(Player p) {
        if (!G.ready(p)) return;
        String key = G.key(p);
        GardenData d = G.data(p);
        if (!d.started) firstVisit(p, d);
        for (String[] n : INSTANCE_NPCS) {
            Location at = G.anchor(key, n[1]);
            if (at != null) Sky.npcs().spawnAt(n[0], key, at);
        }
        visitors.syncNpcs(key, d);
        try { Sky.service(Features.Objectives.class).complete(p, "visit_garden"); } catch (Throwable ignored) { }
    }

    static void firstVisit(Player p, GardenData d) {
        d.started = true;
        String[] starters = {"beginner_3", "beginner_1", "beginner_4", "beginner_2"};
        int free = Math.max(0, Math.min(starters.length, G.freePlots.get()));
        for (int i = 0; i < free; i++) if (!d.plots.containsKey(starters[i])) Desk.unlock(p, d, starters[i]);
        d.nextVisitorAt = System.currentTimeMillis() + 60_000L;          // the first visitor is quick
        d.composter.last = System.currentTimeMillis();
        d.lastSeen = System.currentTimeMillis();
        Msg.box(p, "&a&lWELCOME TO YOUR GARDEN", List.of(
                "&7Your own farm in the sky, &a24 plots &7around the barn.",
                "",
                "&e1. &7Open the &aDesk &7(the lectern in the barn, or &e/desk&7).",
                "&e2. &7Your first plot (south of here) is &afree&7: clear it",
                "&7   with a scythe or lay a &afarm preset &7from the Desk.",
                "&e3. &7Feed crops to the &acomposter&7: compost buys more plots.",
                "&e4. &7Visitors trade &cCopper &7for crops: spend it at Marten's.",
                "&e5. &7Harvest crops here for &aCrop Milestones &7and Garden XP."));
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        if (G.exists("SAM_SCYTHE") && Sky.items().count(p, "SAM_SCYTHE") <= 0) G.give(p, "SAM_SCYTHE", 1, com.skyclone.api.loot.LootSource.REWARD);
    }

    static void leaveGarden(Player p) {
        String key = G.key(p);
        boolean anyone = false;
        for (Player m : G.members(key)) if (m != p && G.onGarden(m) && G.inOwnGarden(m)) anyone = true;
        if (anyone) return;
        for (String[] n : INSTANCE_NPCS) Sky.npcs().despawn(n[0], key);
        visitors.despawnAll(key);
    }

    // =====================================================================================
    // events
    // =====================================================================================

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        GardenData d = G.data(p);
        boolean firstOnline = true;
        for (Player m : G.members(G.key(p))) if (m != p) firstOnline = false;
        if (firstOnline) {
            visitors.offline(p, d);
            pests.offline(p, d);
        }
        G.syncAwards(p);
        Effects.remember(G.member(p));
        if (G.onGarden(p) && G.inOwnGarden(p)) Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> enterGarden(p), 20L);
    }

    @EventHandler
    public void onIsland(SkyEvents.IslandChange e) {
        if (G.ISLAND.equals(e.to)) Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (e.player.isOnline() && G.inOwnGarden(e.player)) enterGarden(e.player); }, 10L);
        else if (G.ISLAND.equals(e.from) && G.ready(e.player)) leaveGarden(e.player);
    }

    @EventHandler
    public void onBuilt(SkyEvents.IslandBuilt e) {
        if (!G.ISLAND.equals(e.islandId) || e.instanceKey == null) return;
        for (Player m : G.members(e.instanceKey)) if (G.onGarden(m) && G.inOwnGarden(m)) enterGarden(m);
    }

    @EventHandler
    public void onDeath(SkyEvents.SkyDeath e) {
        Player p = e.player;
        if (!G.onGarden(p) || !G.ready(p)) return;
        if (G.inOwnGarden(p)) {
            Location spawn = G.anchor(G.key(p), "spawn");
            if (spawn != null) e.respawnAt = spawn;
        }
    }

    @EventHandler
    public void onQuit(SkyEvents.ProfileUnload e) {
        if (e.player != null && G.onGarden(e.player)) leaveGarden(e.player);
    }

    // =====================================================================================
    // sidebar and tab
    // =====================================================================================

    private List<String> sidebar(Player p) {
        if (!G.ready(p) || !G.onGarden(p)) return List.of();
        GardenData d = G.data(p);
        List<String> out = new ArrayList<>();
        out.add("&aGarden " + GardenMath.roman(G.level(d)) + " &7Copper: &c" + Text.commasInt(d.copper));
        out.add("&7Sowdust: &2" + Text.commasInt(Math.floor(d.sowdust)));
        int[] t = G.template(G.key(p), p.getLocation());
        String plot = t == null ? null : G.plotAt(t[0], t[2]);
        GardenData.PlotState s = plot == null ? null : d.plots.get(plot);
        if (s != null && s.pests > 0) out.add("&7" + Text.strip(G.plotName(d, plot)) + " &4ൠ &cx" + s.pests);
        else if (d.livePests() > 0) out.add("&7Pests: &4ൠ &cx" + d.livePests());
        else out.add("&7Visitors: &b" + d.visitors.size());
        return out;
    }

    private List<String> tab(Player p) {
        if (!G.ready(p)) return List.of();
        GardenData d = G.data(p);
        if (!d.started) return List.of();
        List<String> out = new ArrayList<>();
        out.add("&a&lGarden &7Level " + GardenMath.roman(G.level(d)));
        out.add(" &7Copper: &c" + Text.commasInt(d.copper));
        out.add(" &7Sowdust: &2" + Text.commasInt(Math.floor(d.sowdust)));
        out.add(" &7Pests bag: &6" + Text.commasInt(G.member(p).pestBag));
        out.add(" &7Visitors: &b" + d.visitors.size() + "&7/&b" + G.visitorMax.get());
        out.add(" &7Pests: &c" + d.livePests());
        out.add(" &7Composter: &a" + Desk.composterReady(d) + " ready");
        return out;
    }
}
